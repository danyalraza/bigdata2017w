package ca.uwaterloo.cs.bigdata2017w.assignment2

import io.bespin.scala.util.Tokenizer

import org.apache.log4j._
import org.apache.hadoop.fs._
import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.Partitioner
import org.rogach.scallop._

class BigramPairsConf(args: Seq[String]) extends ScallopConf(args) with Tokenizer {
  mainOptions = Seq(input, output, reducers)
  val input = opt[String](descr = "input path", required = true)
  val output = opt[String](descr = "output path", required = true)
  val reducers = opt[Int](descr = "number of reducers", required = false, default = Some(1))
  val numExecutors = opt[Int](descr = "number of executors", required = false, default = Some(1))
  val executorCores = opt[Int](descr = "number of cores", required = false, default = Some(1))
  verify()
}

class MyPartitioner(numParts: Int) extends Partitioner {
  override def numPartitions: Int = numParts
  override def getPartition(key: Any): Int = key match {
     case (left, right) => (left.hashCode() & Integer.MAX_VALUE) % numParts
     case _ => 0
  }
}

object ComputeBigramRelativeFrequencyPairs extends Tokenizer {
  val log = Logger.getLogger(getClass().getName())

  def main(argv: Array[String]) {
    val args = new BigramPairsConf(argv)

    log.info("Input: " + args.input())
    log.info("Output: " + args.output())
    log.info("Number of reducers: " + args.reducers())

    val conf = new SparkConf().setAppName("Compute Bigram Relative Frequency Pairs")
    val sc = new SparkContext(conf)

    val outputDir = new Path(args.output())
    FileSystem.get(sc.hadoopConfiguration).delete(outputDir, true)

    val textFile = sc.textFile(args.input(), args.reducers())
    textFile
      .flatMap(line => {
        val tokens = tokenize(line)
        if (tokens.length > 1) {
          val bigram = tokens.sliding(2).map(p => (p.head, p.last)).toList
          val bigramMarginal = tokens.init.map(w => (w, "*")).toList
          bigram ++ bigramMarginal
        } else List()
      })
      .map(bigram => (bigram, 1))
      .reduceByKey(_ + _)
      .sortByKey()
      .repartitionAndSortWithinPartitions(new MyPartitioner(args.reducers()))
      .mapPartitions(tmp => {
        var marginal = 0.0
        tmp.map(bi => {
          bi._1 match {
            case (_, "*") => {
              marginal = bi._2
              (bi._1, bi._2)
            }
            case (_, _) => (bi._1, bi._2 / marginal)
          }
        })
      })
      .map(p => "(" + p._1._1 + ", " + p._1._2 + "), " + p._2)
      .saveAsTextFile(args.output())
  }
}
