package controllers

import models._
import play.api.db.slick._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.libs.concurrent.Execution.Implicits.defaultContext   
import play.api.mvc.{ Action, Controller }
import play.api.Logger

/** Main application entrypoint.
  *
  * @author Rainer Simon <rainer.simon@ait.ac.at>
  */
object ApplicationController extends Controller with Secured {
  
  private val UTF8 = "UTF-8"
    

  /** Returns the index page for logged-in users **/
  def index = Action.async { implicit request =>
    val u = username(request) 
    val future = scala.concurrent.Future {
      DB.withSession { implicit s:Session =>
        val docs = GeoDocuments.listAll
          .sortBy(d => (d.date, d.author, d.title))
          .map(doc => (doc, doc.totalToponymCount, doc.unverifiedToponymCount))
          
        val user = u.map(u => Users.findByUsername(u)).flatten        
        (docs, user)
      }
    }
    
    if (isAuthorized) {
      future.map(result => Ok(views.html.index(result._1, result._2.get)))
    } else {
      future.map(result => Ok(views.html.index_public(result._1)))
    }
  }
  
  /*
  def index = DBAction { implicit rs => Async { 
    if (isAuthorized) {
      val future = scala.concurrent.Future { }
      future.map(_ => NotFound) // Ok(views.html.index(currentUser.get))
    } else {
      
      val future = scala.concurrent.Future {
        GeoDocuments.listAll
          .sortBy(d => (d.date, d.author, d.title))
          .map(doc => (doc, doc.totalToponymCount, doc.unverifiedToponymCount))
      }
      
      future.map(result => Ok(views.html.index_public(result)))
      

      // Ok(views.html.index_public(documents))
    }
  } }
   */
   
  /** Shows the 'public map' for the specified document.
    *  
    * @param doc the document ID 
    */  
  def showMap(doc: Int) = DBAction { implicit rs =>
    val document = GeoDocuments.findById(doc)
    if (document.isDefined)
      Ok(views.html.public_map(document.get))
    else
      NotFound
  }
    
  /** Shows the text annotation UI for the specified text.
    * 
    * @param text the internal ID of the text in the DB 
    */
  def showTextAnnotationUI(text: Int) = protectedDBAction(Secure.REDIRECT_TO_LOGIN) { username => implicit request => 
    val gdocText = GeoDocumentTexts.findById(text)
    if (gdocText.isDefined) {
      val plaintext = new String(gdocText.get.text, UTF8)
      val annotations = if (gdocText.get.gdocPartId.isDefined) {
          Annotations.findByGeoDocumentPart(gdocText.get.gdocPartId.get)
        } else {
          Annotations.findByGeoDocument(gdocText.get.gdocId)
        }
      
      // Build HTML
      val ranges = annotations.foldLeft(("", 0)) { case ((markup, beginIndex), annotation) => {
        if (annotation.status == AnnotationStatus.FALSE_DETECTION) {
          (markup, beginIndex)
        } else {
          // Use corrections if they exist, or Geoparser results otherwise
          val toponym = if (annotation.correctedToponym.isDefined) annotation.correctedToponym else annotation.toponym
          val offset = if (annotation.correctedOffset.isDefined) annotation.correctedOffset else annotation.offset 
          val url = if (annotation.correctedGazetteerURI.isDefined && !annotation.correctedGazetteerURI.get.trim.isEmpty) 
                      annotation.correctedGazetteerURI
                    else annotation.gazetteerURI

          if (offset.isDefined && offset.get < beginIndex)
            debugTextAnnotationUI(annotation)
          
          val cssClassA = annotation.status match {
            case AnnotationStatus.VERIFIED => "annotation verified"
            case AnnotationStatus.IGNORE => "annotation ignore"
            case AnnotationStatus.NO_SUITABLE_MATCH => "annotation not-identifyable"
            case AnnotationStatus.AMBIGUOUS => "annotation not-identifyable"
            case AnnotationStatus.MULTIPLE => "annotation not-identifyable"
            case AnnotationStatus.NOT_IDENTIFYABLE => "annotation not-identifyable"
            case _ => "annotation" 
          }
          
          val cssClassB = if (annotation.correctedToponym.isDefined) " manual" else " automatic"
   
          val title = "#" + annotation.uuid + " " +
            AnnotationStatus.screenName(annotation.status) + " (" +
            { if (annotation.correctedToponym.isDefined) "Manual Correction" else "Automatic Match" } +
            ")"
            
          if (toponym.isDefined && offset.isDefined) {
            val nextSegment = escapePlaintext(plaintext.substring(beginIndex, offset.get)) +
              "<span data-id=\"" + annotation.uuid + "\" class=\"" + cssClassA + cssClassB + "\" title=\"" + title + "\">" + escapePlaintext(toponym.get) + "</span>"
              
            (markup + nextSegment, offset.get + toponym.get.size)
          } else {
            (markup, beginIndex)
          }
        }
      }}

      val html = (ranges._1 + escapePlaintext(plaintext.substring(ranges._2))).replace("\n", "<br/>")
      
      val gdoc = GeoDocuments.findById(gdocText.get.gdocId)
      val gdocPart = gdocText.get.gdocPartId.map(id => GeoDocumentParts.findById(id)).flatten
      
      val title = gdoc.get.title + gdocPart.map(" - " + _.title).getOrElse("")      
      Ok(views.html.annotation(gdoc.get, textsForGeoDocument(gdoc.get.id.get), username, gdocPart, html))
    } else {
      NotFound(Json.parse("{ \"success\": false, \"message\": \"Annotation not found\" }")) 
    }
  }
  
  private def escapePlaintext(segment: String): String = {
    // Should cover most cases (?) - otherwise switch to Apache Commons StringEscapeUtils
    segment
      .replace("<", "&lt;")
      .replace(">", "&gt;")
  }
  
  /** Helper method that generates detailed debug output for overlapping annotations.
    * 
    * @param annotation the offending annotation
    */
  private def debugTextAnnotationUI(annotation: Annotation)(implicit s: Session) = {
    val toponym = if (annotation.correctedToponym.isDefined) annotation.correctedToponym else annotation.toponym
    Logger.error("Offending annotation: #" + annotation.uuid + " - " + annotation)
    Annotations.getOverlappingAnnotations(annotation).foreach(a => Logger.error("Overlaps with: #" + a.uuid))
  }

  /** Shows the map-based georesolution correction UI for the specified document.
    *
    * @param doc the document ID 
    */
  def showGeoResolutionUI(docId: Int) = protectedDBAction(Secure.REDIRECT_TO_LOGIN) { username => implicit session => 
    val doc = GeoDocuments.findById(docId)
    if (doc.isDefined)
      Ok(views.html.georesolution(doc.get, textsForGeoDocument(docId), username))
    else
      NotFound
  }
  
  /** Shows detailed stats for a specific document **/
  def showDocumentStats(docId: Int) = DBAction { implicit session =>
    val doc = GeoDocuments.findById(docId)
    if (doc.isDefined) {        
      Ok(views.html.document_stats(doc.get, textsForGeoDocument(docId), currentUser.map(_.username)))
    } else {
      NotFound(Json.parse("{ \"success\": false, \"message\": \"Document not found\" }"))
    }
  }
  
  /** Shows the edit history overview page **/
  def showHistory() = DBAction { implicit session =>
    // TODO just a dummy for now
    Ok(views.html.edit_history(EditHistory.getLastN(500))) 
  }
  
  /** Shows the stats history page **/
  def showStats() = DBAction { implicit session =>
    // TODO just a dummy for now
    Ok(views.html.stats(StatsHistory.listAll())) 
  }
  
  /** Helper method to get the texts (and titles) for a specific GeoDocument **/
  private def textsForGeoDocument(docId: Int)(implicit session: Session): Seq[(GeoDocumentText, Option[String])] =
    GeoDocumentTexts.findByGeoDocument(docId).map(text =>
        // If the text is associated with a GDoc part (rather than the GDoc directly), we'll fetch the part title
        (text, text.gdocPartId.map(partId => GeoDocumentParts.findById(partId).map(_.title)).flatten))
    

}