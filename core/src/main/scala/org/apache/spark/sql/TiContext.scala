/*
 * Copyright 2017 PingCAP, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql

import com.pingcap.tikv.tools.RegionUtils
import com.pingcap.tikv.{TiConfiguration, TiSession}
import com.pingcap.tispark._
import com.pingcap.tispark.auth.TiAuthorization
import com.pingcap.tispark.listener.CacheInvalidateListener
import com.pingcap.tispark.statistics.StatisticsManager
import com.pingcap.tispark.utils.TiUtil
import org.apache.spark.SparkConf
import org.apache.spark.internal.Logging
import org.apache.spark.scheduler.{SparkListener, SparkListenerApplicationEnd}
import org.apache.spark.sql.catalyst.catalog._
import org.json4s.DefaultFormats
import org.json4s.JsonAST._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import scalaj.http.Http

import java.lang
import scala.collection.JavaConverters._
import scala.collection.mutable

class TiContext(val sparkSession: SparkSession) extends Serializable with Logging {
  final val version: String = TiSparkVersion.version
  final val conf: SparkConf = sparkSession.sparkContext.conf
  lazy final val tiAuthorization: Option[TiAuthorization] = TiAuthorization.tiAuthorization
  // If enableAuth, get PDAddress from TiDB else from spark conf
  final val tiConf: TiConfiguration = TiUtil.sparkConfToTiConf(
    conf,
    if (TiAuthorization.enableAuth) {
      Option(tiAuthorization.get.getPDAddress())
    } else Option.empty)
  final val tiSession: TiSession = TiSession.getInstance(tiConf)
  lazy val sqlContext: SQLContext = sparkSession.sqlContext

  sparkSession.sparkContext.addSparkListener(new SparkListener() {
    override def onApplicationEnd(applicationEnd: SparkListenerApplicationEnd): Unit = {
      if (tiSession != null) {
        try {
          tiSession.close()
        } catch {
          case e: Throwable => logWarning("fail to close TiSession!", e)
        }
      }
    }
  })

  TiUtil.registerUDFs(sparkSession)
  StatisticsManager.initStatisticsManager(tiSession)
  CacheInvalidateListener
    .initCacheListener(sparkSession.sparkContext, tiSession.getRegionManager)
  tiSession.injectCallBackFunc(CacheInvalidateListener.getInstance())
  val meta: MetaManager = new MetaManager(tiSession.getCatalog)
  val debug: DebugTool = new DebugTool

  // add backtick for table name in case it contains, e.g., a minus sign
  private def getViewName(dbName: String, tableName: String, dbNameAsPrefix: Boolean): String =
    "`" + (if (dbNameAsPrefix) dbName + "_" + tableName else tableName) + "`"

  class DebugTool {
    implicit val formats: DefaultFormats = DefaultFormats

    /**
     * Balance region leaders of a single table.
     *
     * e.g.
     * `balanceRegionByTable("http://172.16.20.3:2379", "tpch_idx", "lineitem", 20)`
     * This method call will try to balance table `lineitem`'s leader distribution by
     * transforming those leaders reside in a single heavily used TiKV to other TiKVs.
     *
     * @param pdAddress The PD address
     * @param dbName    Database name
     * @param tableName Table name
     * @param maxTrans  Maximum number of transformations this function can perform
     * @return The re-distributed information of original table
     */
    def balanceRegionByTable(
        pdAddress: String,
        dbName: String,
        tableName: String,
        maxTrans: Int = 50): Map[String, Integer] = {
      val regionIDPrefix = "pd/api/v1/region/id"
      val operatorsPrefix = "pd/api/v1/operators"
      val storeRegionId = RegionUtils.getStoreRegionIdDistribution(tiSession, dbName, tableName)
      val storeRegionCount = mutable.Map[Long, Long]()

      storeRegionId.asScala.foreach((tuple: (lang.Long, java.util.List[lang.Long])) => {
        storeRegionCount(tuple._1) = tuple._2.size()
      })

      val avgRegionCount = storeRegionCount.values.sum / storeRegionCount.size

      var transCount = 0
      storeRegionId.asScala
        .flatMap(_._2.asScala)
        .foreach((regionId: lang.Long) => {
          val resStr = Http(s"$pdAddress/$regionIDPrefix/$regionId").asString
          val json: JValue = parse(resStr.body)
          val leader = (json \ "leader").extract[JObject]
          val peers = (json \ "peers").extract[JArray].arr
          val leaderStoreId = (leader \ "store_id").extract[Long]

          val targetLeaders = peers
            .map(x => (x \ "store_id").extract[Long])
            .filterNot(_ == leaderStoreId)
            .filter(id =>
              storeRegionCount.contains(id) &&
                storeRegionCount(id) < storeRegionCount(leaderStoreId) &&
                storeRegionCount(id) < avgRegionCount)

          if (targetLeaders.nonEmpty && transCount < maxTrans) {
            val toStore = targetLeaders.minBy(storeRegionCount(_))
            val req = ("name" -> "transfer-leader") ~ ("region_id" -> JDecimal(
              BigDecimal(regionId))) ~ ("to_store_id" -> JDecimal(BigDecimal(toStore)))
            val resp = Http(s"$pdAddress/$operatorsPrefix")
              .postData(compact(render(req)))
              .header("content-type", "application/json")
              .asString
            if (resp.isSuccess) {
              logInfo(
                s"Transfer $regionId leader :Store $leaderStoreId to Store $toStore successfully")
              storeRegionCount(leaderStoreId) -= 1
              storeRegionCount(toStore) += 1
            } else {
              logError(
                s"Transfer $regionId leader :Store $leaderStoreId to Store $toStore failed -- ${resp.body}")
            }
            transCount += 1
          }
        })
      getRegionDistribution(dbName, tableName)
    }

    def getRegionDistribution(dbName: String, tableName: String): Map[String, Integer] =
      RegionUtils.getRegionDistribution(tiSession, dbName, tableName).asScala.toMap
  }
}
