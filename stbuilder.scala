class STBuilder(indentStr:String="  ",tags2Alias:String="t",backEnd:String="Text") {
  import org.jsoup.nodes._
  import scala.collection.JavaConversions._
  import STBuilder.{tagsSet,tagRenames,attrNameMap,escape,bestStringRep}
  
  def imports=
    s"import scalatags.$backEnd.all._,scalatags.$backEnd.{tags2=>$tags2Alias}\n"
  def prelude(packageName: String,objectName: String) =
      s"package $packageName\n\nobject $objectName {\n$indentStr$imports\n"
  def postlude="}\n"
  /** Calculate the identifier for a given attribute name */
  def attrName(an: String) = 
    attrNameMap.get(an).getOrElse('"'+an+"\".attr")
  /** Calculate the right identifier for a given tag name */
  def tagName(tagN: String) = {
    val tn=tagRenames.get(tagN).getOrElse(tagN)
    if (tagsSet.contains(tn)) tn
    else s"$tags2Alias.$tn"
  }
  /** Build a method called methodName that will return an expression for a given node n.
   * contentContainerNodes specifies nodes of which the content in the original node will not 
   * be included in the method, but the method will get a parameter of type Frag* named by the 
   * second element of the tuple, with which content can dynamically be inserted into the layout.
   * It is important that the parameter names specified in this way do not conflict with any
   * identifier from the "all" bundle of ScalaTags.
    */     
  def buildLayout(n: Node, methodName: String, startTab: Int=1)(contentContainerNodes: (Element,String)*) = {
    val params=contentContainerNodes.toSeq
    val pMap=params.toMap
    def buildNode(n: Node,tab: Int):String = {
      val tabs=indentStr*tab     
      n match {                                                                             
        case e: Element => 
          val attr=e.attributes.iterator.map{a=>val v=escape(a.getValue); s"${attrName(a.getKey)}:=$v"}
          val content=pMap.get(e).map { paramName =>
            attr.map(_.toString+", ").mkString + paramName+".toSeq"
          }.getOrElse {
            val ch=e.childNodes.map(c=>buildNode(c,tab+1)).filter(_.nonEmpty)
            val post=if (ch.length>1 || ch.headOption.filter(_.contains('\n')).isDefined) s"\n$tabs" else "" 
            (attr++ch).mkString(",")+post
          }
          s"\n$tabs${tagName(e.tagName)}($content)"
        case d: DataNode => bestStringRep(d.getWholeData)
        case t: TextNode => 
          if (t.isBlank) ""
          else bestStringRep(t.text.trim)
        case _ => ""//DocumentType,XmlDeclaration,Comment
      } 
    } 
    val paramStr=params.map(p=>s"(${p._2}: Frag*)").mkString
    indentStr*startTab+s"def $methodName$paramStr = ${buildNode(n,startTab+1)}\n"
  }      
}

object STBuilder {
  import scala.reflect.runtime.{universe => ru}
  import scalatags.{Text=>STT},scalatags.generic.{Attr,TypedTag}
  val tripQuote="\""*3
  /** Provide a safe source code representation of a given string */
  def escape(s: String): String = ru.Literal(ru.Constant(s)).toString
  /** Determine if a given string can be safely represented inside triple quotes */
  def tripQuoteSafe(s: String) = !(s.contains(tripQuote)||s.contains("\\u"))
  /** Pick the best way to represent a given string in source code: non-trivial multiline strings go 
  in triple quotes if safe to do so; in all other cases the string is escaped. */
  def bestStringRep(s: String) = 
    if (s.length>15 && s.contains("\n") && tripQuoteSafe(s)) tripQuote+s+tripQuote
    else escape(s)
  lazy val keywords=ru.asInstanceOf[scala.reflect.internal.SymbolTable].nme.keywords.map(_.toString)
  /** check that a given string isn't a Scala keyword */
  def nonKeyword(s: String) = !(keywords.contains(s))
  /** Find a list of values of type b belonging to the object a */
  def valsOf[A: ru.TypeTag](a:A,b:ru.Type) = {                                   
    ru.typeOf[A].members.filter(_.isMethod).map(_.asMethod).filter(m=>m.isGetter&&m.returnType<:<b)
  } 
  lazy val mirror = ru.runtimeMirror(getClass.getClassLoader)
  /** A map of known attribute identifiers (expressed as valid Scala code) */  
  lazy val attrNameMap={
    val im=mirror.reflect(STT.attrs)
    valsOf(STT.attrs,ru.typeOf[Attr]).map(m=>m.name->(im reflectMethod m)()).collect {
      case (n,x: scalatags.generic.Attr) => x.name->n.toString
    }.groupBy(_._1).mapValues(_.map(_._2)) //this part picks out the "best" identifier if 
      .mapValues(l=>l.find(nonKeyword).getOrElse(s"`${l.head}`"))  // more than one matches
  }
  /** A list of known identifiers in the "tags" (and thus in the "all") bundle. 
  Assumes that none are keywords and that all symbol names match their respective 
  tag names (which is true for ScalaTags 0.5.1) */
  lazy val tagsSet=
    valsOf(STT.tags,ru.typeOf[TypedTag[_,_,_]]).map(_.name.toString).toSet
  /** A map of necessary tag renames (for missing elements) */
  val tagRenames=Map(
    "hgroup"->"div"
  )
}

class STFileBuilder(fileName: String, packageName: String, objectName: String, 
    indentStr:String="  ", tags2Alias:String="t", backEnd:String="Text") {
  import org.jsoup.nodes._
  val builder=new STBuilder(indentStr,tags2Alias,backEnd)
  val p=new java.io.PrintWriter(fileName)
  p.write(builder.prelude(packageName,objectName))
  
  def addLayout(n: Node, methodName: String, startTab: Int=1)(paramNodes: (Element,String)*) =
    p.write(builder.buildLayout(n,methodName,startTab)(paramNodes:_*)+"\n")
  
  def finish = {
    p.write(builder.postlude)
    p.close
  }
} 