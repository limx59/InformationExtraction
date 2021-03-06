package org.apache.spark.sql

import java.io.File

import com.intel.ie.SparkInteractiveDriver
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.functions._
import org.apache.spark.{SparkConf, SparkContext}
import scopt.OptionParser

case class RelationRow(
  company: String,
  name: String,
  relation: String,
  entity: String,
  text: String
)

case class PageResult(company: String, extracted: Long, labelled: Long, correct: Long, wrong: Long, missed: Long)

object RelationEvaluation {
  case class Params(
   textPath: String = null,
   labelPath: String = null,
   partitionSize: Int = 8,
   withDetail: Boolean = false)
  
  def main(args: Array[String]) {
    val defaultParams = Params()

    val parser = new OptionParser[Params]("RelationEvaluation") {
      head("Relation Revaluation System")
      opt[Int]("partitionSize")
        .text(s"partition size, default: ${defaultParams.partitionSize}")
        .action((x, c) => c.copy(partitionSize = x))
      opt[Boolean]("withDetail")
        .text(s"whether to show detailed evaluation result for each company, default: ${defaultParams.withDetail}")
        .action((x, c) => c.copy(withDetail = x))
      arg[String]("<textPath>")
        .text("input path to extract relations")
        .required()
        .action((x, c) => c.copy(textPath = x))
      arg[String]("<labelPath>")
        .text("input path which has already labeled entity")
        .required()
        .action((x, c) => c.copy(labelPath = x))
    }

    parser.parse(args, defaultParams) match {
      case Some(params) => run(params)
      case _ => sys.exit(1)
    }
  }

  def run(params: Params): Unit = {
    println(s"RelationEvaluation with parameters:\n$params")

    Logger.getLogger("org").setLevel(Level.WARN)
    Logger.getLogger("intel").setLevel(Level.WARN)
    Logger.getLogger("edu").setLevel(Level.WARN)
    
    val sc = SparkContext.getOrCreate(
      new SparkConf()
        .setAppName(this.getClass.getSimpleName)
    )
    sc.hadoopConfiguration.set("mapreduce.input.fileinputformat.input.dir.recursive", "true")

    val textPath = params.textPath // "data/evaluation/web/"
    val labelPath = params.labelPath // "data/evaluation/extraction"
    val partitionSize = params.partitionSize
    
    val sqlContext = SQLContext.getOrCreate(sc)
    val companyList = //Array("NCR Corporation")    
      sc.wholeTextFiles(labelPath, partitionSize).map { case (title, content) =>
        new File(new File(title).getParent).getName
      }.collect()

    val st = System.nanoTime()

    val extractionResult = sc.wholeTextFiles(textPath, partitionSize)
      .filter { case (title, content) =>
        val companyName = new File(new File(title).getParent).getName
        companyList.contains(companyName)
      }.flatMap { case (title, content) =>
        val companyName = new File(new File(title).getParent).getName
        content.split("\n")
//          .map(line => if (line.length > 500) line.substring(0, 500) else line)
          .map(line => line.replaceAll("\\(|\\)|\"|\"|``|''", "").replace("  ", " "))
          .flatMap(line => SparkInteractiveDriver.getWorkRelation(line))
          //        .map(rl => (companyName, rl))
          .map(t => RelationRow(companyName, t.name, t.relation, t.entity, t.text))
      }
    val extractedDF = sqlContext.createDataFrame(extractionResult).cache()

    val labelledResult = sc.wholeTextFiles(labelPath, partitionSize)
      .filter { case (title, content) =>
        val companyName = new File(new File(title).getParent).getName
        companyList.contains(companyName)
      }.flatMap { case (title, content) =>
      val companyName = new File(new File(title).getParent).getName
      content.split("\n").filter(_.nonEmpty).map { line =>
        val elements = line.replaceAll("\u00a0", " ").replaceAll("\u200B|\u200C|\u200D|\uFEFF|\\(|\\)|\"|\"|``|''", "")
          .replace("  ", " ").split("\t")
        RelationRow(companyName, elements(0), elements(1), elements(2), elements(3))
      }
    }
    val labelledDF = sqlContext.createDataFrame(labelledResult).cache()

    getResultForOneRelation("all", extractedDF, labelledDF, "title", sc)
    println((System.nanoTime() - st) / 1e9 + " seconds")

    // details evaluation    
    if (params.withDetail) {
      val pageResults = companyList.map { companyName =>
        val extractedFiltered = extractedDF.where(col("company") === companyName).cache()
        val labelledFiltered = labelledDF.where(col("company") === companyName).cache()
        val pageResult = getResultForOneRelation(companyName, extractedFiltered, labelledFiltered, "title", sc)
        extractedFiltered.unpersist()
        labelledFiltered.unpersist()
        pageResult
      }.toSeq.toArray

      println(s"Overall results: ${pageResults.size} companies evaluated")
      def printResultForOneRelation(relation: String, index: Int): Unit = {
        SQLContext.getOrCreate(sc).createDataFrame(sc.parallelize(pageResults.map(_ (index)))).show(300, false)
        val totalCorrect = pageResults.map(_ (index).correct).sum
        val totalWrong = pageResults.map(_ (index).wrong).sum
        val totalMissed = pageResults.map(_ (index).missed).sum
        val recall = totalCorrect.toDouble / (totalCorrect + totalMissed)
        val precision = totalCorrect.toDouble / (totalCorrect + totalWrong)
        println(s"overall result for $relation --- recall: $recall. precision: $precision")
      }
      printResultForOneRelation("title", 0)

      extractedDF.unpersist()
      labelledDF.unpersist()
    }
  }

  def getResultForOneRelation(company: String, extractedRawDF: DataFrame, labelledRawDF: DataFrame,
                              relationType: String, sc: SparkContext): Array[PageResult] = {

    val extractedLowerDF = extractedRawDF
      .where(col("relation").isin(relationType))
      .select(lower(extractedRawDF("name")).alias("name"), extractedRawDF("relation"),
        lower(extractedRawDF("entity")).alias("entity"), extractedRawDF("text"))
    val extractedDF = extractedLowerDF
      .select("name", "relation", "entity")
      .distinct()
      .cache()

    val labelledLowDF = labelledRawDF
      .where(col("relation").isin(relationType))
      //      .where(col("relation").isin(relationType))
      .select(lower(labelledRawDF("name")).alias("name"), labelledRawDF("relation"),
      lower(labelledRawDF("entity")).alias("entity"), labelledRawDF("text"))
    val labelledDF = labelledLowDF
      .select("name", "relation", "entity")
      .distinct()
      .cache()

    val correctDF = labelledDF.intersect(extractedDF).distinct().cache()

    println(company)
    println(Console.BLUE + "correct:")
    println(correctDF.showString(100, false))

    println(Console.RED + "missed:")
    println(labelledDF.except(correctDF).join(labelledLowDF, Seq("name", "relation", "entity"))
      .distinct().showString(100, false))

    println("wrong:")
    println(Console.RED + extractedDF.except(correctDF).join(extractedLowerDF, Seq("name", "relation", "entity"))
      .distinct().showString(100, false))

    val extractedCt = extractedDF.count()
    val labelledCt = labelledDF.count()
    val correctCt = correctDF.count()
    val recall = correctCt.toDouble / labelledCt
    val precision = correctCt.toDouble / (if (extractedCt == 0) 1 else extractedCt)
    println(Console.YELLOW_B + s"recall: $recall. precision: $precision. (" +
      s"extracted: ${extractedCt}; labelled: ${labelledCt}; correct: ${correctCt})")
    println(Console.RESET)

    labelledDF.unpersist()
    extractedDF.unpersist()
    correctDF.unpersist()
    Array(PageResult(company, extractedCt, labelledCt, correctCt, extractedDF.count() - correctCt,
      labelledDF.count() - correctCt))
  }
}