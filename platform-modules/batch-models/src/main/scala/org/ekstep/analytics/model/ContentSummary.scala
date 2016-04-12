package org.ekstep.analytics.model

import org.ekstep.analytics.framework.IBatchModel
import org.ekstep.analytics.framework._
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import scala.collection.mutable.Buffer
import org.ekstep.analytics.framework.util.CommonUtil
import org.ekstep.analytics.framework.util.JSONUtils
import scala.collection.mutable.HashMap
import java.text.SimpleDateFormat
import java.util.Date
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import org.joda.time.LocalDate
import org.joda.time.DateTime

class Summary(val contentId: String,val ver: String, val dtRange: DtRange,val syncDate: Long, 
                 val loc: Option[String], val timeSpent: Double, val numSessions: Long, val averageTsSession: Double, 
                 val interactionsMinSession: List[Double], val averageInteractionsMin: Double, 
                 val numSessionsWeek: Long, val tsWeek: Double) extends Serializable {};
case class Content_Summary(content_id: String,start_date:DateTime, total_ts:Double,total_num_sessions:Long,
                           average_ts_session:Double,interactions_min_session:List[Double],average_interactions_min:Double,
                           num_sessions_week:Long,ts_week:Double)
                 
object ContentSummary extends IBatchModel[MeasuredEvent] with Serializable {
  
  def execute(data: RDD[MeasuredEvent], jobParams: Option[Map[String, AnyRef]])(implicit sc: SparkContext): RDD[String] = {
    println("### Running the model ContentSummary ###");
    val filteredEvents = DataFilter.filter(data, Filter("eid", "EQ", Option("ME_SESSION_SUMMARY")));
    val sortedEvents = filteredEvents.sortBy { x => x.ets };
    println("### Broadcasting data to all worker nodes ###");
    val config = jobParams.getOrElse(Map[String, AnyRef]());
    val configMapping = sc.broadcast(config);
    val deviceMapping = sc.broadcast(JobContext.deviceMapping);
    
    val contentMap = sortedEvents.groupBy { x => x.dimensions.gdata.get.id }
    val contentSummary = contentMap.mapValues { events =>
      val firstEvent = events.head
      val lastEvent = events.last
      val gameId = firstEvent.dimensions.gdata.get.id
      val gameVersion = firstEvent.ver
      //println(JSONUtils.serialize(lastEvent))
      val eventStartTimestamp = firstEvent.context.date_range.from
      val eventEndTimestamp = lastEvent.context.date_range.to
      val currentDate = new LocalDate(eventStartTimestamp)
      val startDate = new LocalDate(1450523803000l)
      val numSessions = events.size
      val timeSpent = events.map{x => 
          (x.edata.eks.asInstanceOf[Map[String, AnyRef]].get("timeSpent").get.asInstanceOf[Double])
      }.sum
      val averageTsSession = timeSpent/numSessions  
      val interactionsMinSession = events.map{ x => 
          (x.edata.eks.asInstanceOf[Map[String, AnyRef]].get("interactEventsPerMin").get.asInstanceOf[Double])
      }.asInstanceOf[List[Double]]
      val averageInteractionsMin = ((interactionsMinSession.map(x => x).sum)/interactionsMinSession.size)
      val numSessionsWeek = numSessions/getWeeksBetween(startDate,currentDate)
      val tsWeek = timeSpent/getWeeksBetween(startDate,currentDate)
      new Summary(gameId,gameVersion,DtRange(eventStartTimestamp,eventEndTimestamp), lastEvent.syncts, 
               None, timeSpent, numSessions, averageTsSession, interactionsMinSession.asInstanceOf[List[Double]], 
               averageInteractionsMin, numSessionsWeek, tsWeek )
    }
    contentSummary.map(f => {
            getMeasuredEvent(f, configMapping.value);
        }).map { x => JSONUtils.serialize(x) };
  }

  private def getMeasuredEvent(userMap: (String, Summary), config: Map[String, AnyRef]): MeasuredEvent = {
        val game = userMap._2;
        val mid = CommonUtil.getMessageId("ME_CONTENT_SUMMARY", null, "CONTENT", game.dtRange, game.contentId);
        val measures = Map(
            "timeSpent" -> game.timeSpent,
            "numSessions" -> game.numSessions,
            "averageTsSession" -> game.averageTsSession,
            "interactionsMinSession" -> game.interactionsMinSession,
            "averageInteractionsMin" -> game.averageInteractionsMin,
            "numSessionsWeek" -> game.numSessionsWeek,
            "tsWeek" -> game.tsWeek);
        MeasuredEvent("ME_CONTENT_SUMMARY", System.currentTimeMillis(), game.syncDate, "1.0", mid, Option(userMap._1), None, None,
            Context(PData(config.getOrElse("producerId", "AnalyticsDataPipeline").asInstanceOf[String], config.getOrElse("modelId", "ContentSummary").asInstanceOf[String], config.getOrElse("modelVersion", "1.0").asInstanceOf[String]), None, "CONTENT", game.dtRange),
            Dimensions(None, None, None, None, None, None, None),
            MEEdata(measures));
    
  }
  private def getWeeksBetween(fromDate: LocalDate, toDate: LocalDate): Int = {
    val dates = CommonUtil.datesBetween(fromDate,toDate)
    val weeks = dates.size/7
    weeks
  }
}