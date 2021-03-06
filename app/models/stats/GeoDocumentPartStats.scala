package models.stats

import play.api.db.slick._
import models.{ Annotations, AnnotationStatus }

/** A helper trait that provides basic stats & metrics for a GeoDocumentPart.
  *
  * @author Rainer Simon <rainer.simon@ait.ac.at> 
  */
trait GeoDocumentPartStats {
  
  val id: Option[Int]
  
  /** Total # of toponyms in the document part, i.e. all that were not marked as 'false detection' **/
  def totalToponymCount()(implicit s: Session): Int =
    Annotations.countForGeoDocumentPartAndStatus(id.get, AnnotationStatus.VERIFIED, AnnotationStatus.NOT_VERIFIED, AnnotationStatus.NOT_IDENTIFYABLE)
  
  /** Returns the ratio of manually processed vs. total toponyms in the document part **/
  def completionRatio()(implicit s: Session): Double = {
    val valid = Annotations.findByGeoDocumentPart(id.get).filter(_.status != AnnotationStatus.FALSE_DETECTION)
    val unprocessed = valid.filter(_.status == AnnotationStatus.NOT_VERIFIED)
    (valid.size - unprocessed.size).toDouble / valid.size.toDouble
  }

}