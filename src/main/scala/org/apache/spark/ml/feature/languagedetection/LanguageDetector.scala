package org.apache.spark.ml.feature.languagedetection

import java.nio.charset.Charset
import java.util

import org.apache.spark.broadcast.Broadcast
import org.apache.spark.ml.Estimator
import org.apache.spark.ml.param.shared.{HasInputCol, HasLabelCol}
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.types.StructType


object LanguageDetector {

  /**
    * Compute grams for each text
    * @param data
    * @param gramLengths
    * @return
    */
  private[this] def computeGrams(data: Dataset[(String, String)],
                   gramLengths: Seq[Int]): Dataset[(String, Seq[Byte], Int)] = {
    import data.sparkSession.implicits._

    data
      .flatMap{
        case (lang, text) =>
          gramLengths
            .flatMap{
              gramLength =>
                // Compute the occurrences of gram per language
                text
                  .getBytes(Charset.forName("UTF-8"))
                  .toSeq
                  .sliding(gramLength)
                  .toSeq
                  .groupBy(identity)
                  .mapValues(_.size)
                  .map{case (gram, count) => (lang, gram, count)}
            }
      }
  }

  /**
    * For each gram: Sum up the counts
    * @param grams
    */
  private[this] def reduceGrams( grams: Dataset[(String, Seq[Byte], Int)],
                   supportedLanguages: Seq[String]
                 ): Dataset[(String, Seq[Byte], Int)] = {
    import grams.sparkSession.implicits._

    supportedLanguages
      .map{
        lang => grams
          .filter(a => a._1 == lang.toString)
          .groupByKey{case (language, gram, count) => gram}
          .reduceGroups((a,b) => (a._1, a._2, a._3 + b._3))
          .map{case (key, rest) => rest}
      }
      .reduce(_ union _)
  }


  /**
    * Compute the probability of occurrence of a gram in each language
    * We use the odds as probability: #Occurrence in language / #Overall
    * and compute Log(1.0 + P)
    */

  private[this] def computeProbabilities(grams: Dataset[(String, Seq[Byte], Int)],
                           supportedLanguages: Seq[String]): Dataset[(Seq[Byte], Array[Double])] = {
    import grams.sparkSession.implicits._

    grams
      .groupByKey{case (lang, gram, count) => gram}
      .mapGroups{
        case (gram, it) =>
          val itSeq = it.toSeq

          val langProbs = supportedLanguages
            .map{lang => itSeq.count(_._1 == lang).toDouble / itSeq.size.toDouble}
            .map(d => Math.log(1.0 + d))
            .toArray

          (gram, langProbs)
      }
  }


  /**
    * For each language l take the top k grams that have a high value of P(l) for each
    *
    * @param gramProbabilities
    */
  private[this] def filterTopGrams(
                      gramProbabilities: Dataset[(Seq[Byte], Array[Double])],
                      supportedLanguages: Seq[String],
                      languageProfileSize: Int
                    ) = {
    import gramProbabilities.sparkSession.implicits._

    val topGramSet: Set[Seq[Byte]] = supportedLanguages
      .indices
      .flatMap(i =>
        gramProbabilities
          .map{ case (gram, probs) => (gram, probs(i))}
          .sort($"_2".desc)
          .take(languageProfileSize)
          .map(_._1)
      )
      .toSet

    val bTopGramSet: Broadcast[Set[Seq[Byte]]] = gramProbabilities
      .sparkSession
      .sparkContext
      .broadcast(topGramSet)

    gramProbabilities
      .filter { gramProbPair =>
        bTopGramSet.value.contains{gramProbPair._1}
      }
  }
//  bTopGramSet.value.exists{arr => arr sameElements gramProbPair._1}

  /**
    * Compute the probabilitie of a n-gram occurring in a particular language.
    * The gram is modeled as an array of bytes, the probability vector is an array of doubles
    * @param data Training data, Dataset[(Language, Fulltext)], i.e. Wikipedia dump
    * @param gramLengths Seq[Int] of the gram sizes that should be used
    * @param languageProfileSize Int, number of top-k grams that should be used
    * @param supportedLanguages Array[Language] of languages that can be detected. This is
    *                           also the order of the probability vector that is computed
    * @return
    */
  def computeGramProbabilities(
                                data: Dataset[(String, String)],
                                gramLengths: Seq[Int],
                                languageProfileSize: Int,
                                supportedLanguages: Seq[String]
                             ): Dataset[(Seq[Byte], Array[Double])] = {
    // Compute all grams from
    val grams = computeGrams(data, gramLengths).cache()

    grams.show()

    // Merge the counts for the grams of
    val reducedGrams = reduceGrams(grams, supportedLanguages).cache()

    reducedGrams.show()

    // For each gram: Compute the probability of occurrence for each language
    val probabilities = computeProbabilities(reducedGrams, supportedLanguages).cache()

    probabilities.show(100)

    // Filter the n-grams for their language probability: Take only the top k values
    val topGrams = filterTopGrams(probabilities, supportedLanguages, languageProfileSize)

    topGrams.show()

    topGrams

  }
}


class LanguageDetector(
                        val uid: String,
                        val supportedLanguages: Seq[String],
                        val gramLengths: Seq[Int],
                        val languageProfileSize: Int
                      )
  extends Estimator[LanguageDetectorModel]
    with HasInputCol with HasLabelCol {

  def this(
            supportedLanguages: Seq[String],
            gramLengths: Seq[Int],
            languageProfileSize: Int) = this(
    Identifiable.randomUID("LanguageDetector"),
    supportedLanguages,
    gramLengths,
    languageProfileSize
  )

  setDefault(
    inputCol -> "fulltext",
    labelCol -> "lang"
  )

  override def transformSchema(schema: StructType): StructType = schema
  override def copy(extra: ParamMap): Estimator[LanguageDetectorModel] = defaultCopy(extra)

  override def fit(dataset: Dataset[_]): LanguageDetectorModel = {
    import dataset.sparkSession.implicits._

    // InputTrainingData is a dataset [String, Int, String] which is [LanguageName, Id, Fulltext]
    val inputTrainingData = dataset
      .select($(labelCol), $(inputCol))
      .as[(String, String)]
      .filter(langTextPair => supportedLanguages.contains(langTextPair._1))
      .cache()


    // Check if input training data contains values for each language
    supportedLanguages.foreach(
      lang =>
        inputTrainingData.filter(langTextPair => langTextPair._1 == lang).count match {
          case 0 => throw new Exception(s"No training examples found for language $lang. Provide examples for each language")
          case _ =>
        }
    )

    val gramProbabilities: Dataset[(Seq[Byte], Array[Double])] = LanguageDetector.computeGramProbabilities(
      inputTrainingData,
      gramLengths,
      languageProfileSize,
      supportedLanguages
    )

    val probabilitiesMap: Map[Seq[Byte], Array[Double]] = gramProbabilities
      .collect
      .toMap

    new LanguageDetectorModel(
      gramProbabilities = probabilitiesMap,
      gramLengths = gramLengths,
      languages = supportedLanguages
    )
  }
}