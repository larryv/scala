/**
 * @author Damien Obrist
 * @author Vlad Ureche
 */
package scala.tools.nsc
package doc
package html
package page
package diagram

import scala.xml.{NodeSeq, XML, PrefixedAttribute, Elem, MetaData, Null, UnprefixedAttribute}
import scala.collection.immutable._
import javax.xml.parsers.SAXParser
import model._
import model.diagram._

class DotDiagramGenerator(settings: doc.Settings) extends DiagramGenerator {

  // the page where the diagram will be embedded
  private var page: HtmlPage = null
  // path to the "lib" folder relative to the page
  private var pathToLib: String = null
  // maps nodes to unique indices
  private var node2Index: Map[Node, Int] = null
  // maps an index to its corresponding node
  private var index2Node: Map[Int, Node] = null
  // true if the current diagram is a class diagram
  private var isClassDiagram = false
  // incoming implicit nodes (needed for determining the CSS class of a node)
  private var incomingImplicitNodes: List[Node] = List()
  // the suffix used when there are two many classes to show
  private final val MultiSuffix = " classes/traits"
  // used to generate unique node and edge ids (i.e. avoid conflicts with multiple diagrams)
  private var counter = 0

  def generate(diagram: Diagram, template: DocTemplateEntity, page: HtmlPage):NodeSeq = {
    counter = counter + 1;
    this.page = page
    pathToLib = "../" * (page.templateToPath(template).size - 1) + "lib/"
    val dot = generateDot(diagram)
    generateSVG(dot, template)
  }

  /**
   * Generates a dot string for a given diagram.
   */
  private def generateDot(d: Diagram) = {
    // inheritance nodes (all nodes except thisNode and implicit nodes)
    var nodes: List[Node] = null
    // inheritance edges (all edges except implicit edges)
    var edges: List[(Node, List[Node])] = null

    // timing
    var tDot = -System.currentTimeMillis

    // variables specific to class diagrams:
    // current node of a class diagram
    var thisNode:Node = null
    var subClasses = List[Node]()
    var superClasses = List[Node]()
    var incomingImplicits = List[Node]()
    var outgoingImplicits = List[Node]()
    var subClassesTooltip: Option[String] = None
    var superClassesTooltip: Option[String] = None
    var incomingImplicitsTooltip: Option[String] = None
    var outgoingImplicitsTooltip: Option[String] = None
    isClassDiagram = false

    d match {
      case ClassDiagram(_thisNode, _superClasses, _subClasses, _incomingImplicits, _outgoingImplicits) =>

        def textTypeEntity(text: String) =
          new TypeEntity {
            val name = text
            def refEntity: SortedMap[Int, (TemplateEntity, Int)] = SortedMap()
          }

        // it seems dot chokes on node names over 8000 chars, so let's limit the size of the string
        // conservatively, we'll limit at 4000, to be sure:
        def limitSize(str: String) = if (str.length > 4000) str.substring(0, 3996) + " ..." else str

        // avoid overcrowding the diagram:
        //   if there are too many super / sub / implicit nodes, represent
        //   them by on node with a corresponding tooltip
        superClasses = if (_superClasses.length > settings.docDiagramsMaxNormalClasses.value) {
          superClassesTooltip = Some(limitSize(_superClasses.map(_.tpe.name).mkString(", ")))
          List(NormalNode(textTypeEntity(_superClasses.length + MultiSuffix), None))
        } else _superClasses

        subClasses = if (_subClasses.length > settings.docDiagramsMaxNormalClasses.value) {
          subClassesTooltip = Some(limitSize(_subClasses.map(_.tpe.name).mkString(", ")))
          List(NormalNode(textTypeEntity(_subClasses.length + MultiSuffix), None))
        } else _subClasses

        incomingImplicits = if (_incomingImplicits.length > settings.docDiagramsMaxImplicitClasses.value) {
          incomingImplicitsTooltip = Some(limitSize(_incomingImplicits.map(_.tpe.name).mkString(", ")))
          List(ImplicitNode(textTypeEntity(_incomingImplicits.length + MultiSuffix), None))
        } else _incomingImplicits

        outgoingImplicits = if (_outgoingImplicits.length > settings.docDiagramsMaxImplicitClasses.value) {
          outgoingImplicitsTooltip = Some(limitSize(_outgoingImplicits.map(_.tpe.name).mkString(", ")))
          List(ImplicitNode(textTypeEntity(_outgoingImplicits.length + MultiSuffix), None))
        } else _outgoingImplicits

        thisNode = _thisNode
        nodes = List()
        edges = (thisNode -> superClasses) :: subClasses.map(_ -> List(thisNode))
        node2Index = (thisNode::subClasses:::superClasses:::incomingImplicits:::outgoingImplicits).zipWithIndex.toMap
        isClassDiagram = true
        incomingImplicitNodes = incomingImplicits
      case _ =>
        nodes = d.nodes
        edges = d.edges
        node2Index = d.nodes.zipWithIndex.toMap
        incomingImplicitNodes = List()
    }
    index2Node = node2Index map {_.swap}

    val implicitsDot = {
      if (!isClassDiagram) ""
      else {
        // dot cluster containing thisNode
        val thisCluster = "subgraph clusterThis {\n" +
          "style=\"invis\"\n" +
          node2Dot(thisNode, None) +
        "}"
        // dot cluster containing incoming implicit nodes, if any
        val incomingCluster = {
          if(incomingImplicits.isEmpty) ""
          else "subgraph clusterIncoming {\n" +
            "style=\"invis\"\n" +
            incomingImplicits.reverse.map(n => node2Dot(n, incomingImplicitsTooltip)).mkString +
            (if (incomingImplicits.size > 1)
              incomingImplicits.map(n => "node" + node2Index(n)).mkString(" -> ") +
              " [constraint=\"false\", style=\"invis\", minlen=\"0.0\"];\n"
            else "") +
          "}"
        }
        // dot cluster containing outgoing implicit nodes, if any
        val outgoingCluster = {
          if(outgoingImplicits.isEmpty) ""
          else "subgraph clusterOutgoing {\n" +
            "style=\"invis\"\n" +
            outgoingImplicits.reverse.map(n => node2Dot(n, outgoingImplicitsTooltip)).mkString +
            (if (outgoingImplicits.size > 1)
              outgoingImplicits.map(n => "node" + node2Index(n)).mkString(" -> ") +
              " [constraint=\"false\", style=\"invis\", minlen=\"0.0\"];\n"
            else "") +
          "}"
        }

        // assemble clusters into another cluster
        val incomingTooltip = incomingImplicits.map(_.name).mkString(", ") + " can be implicitly converted to " + thisNode.name
        val outgoingTooltip =  thisNode.name + " can be implicitly converted to " + outgoingImplicits.map(_.name).mkString(", ")
        "subgraph clusterAll {\n" +
      	"style=\"invis\"\n" +
          outgoingCluster + "\n" +
      	  thisCluster + "\n" +
      	  incomingCluster + "\n" +
      	  // incoming implicit edge
      	  (if (!incomingImplicits.isEmpty) {
      	    val n = incomingImplicits.last
      	    "node" + node2Index(n) +" -> node" + node2Index(thisNode) +
      	    " [id=\"" + cssClass(n, thisNode) + "|" + node2Index(n) + "_" + node2Index(thisNode) + "\", tooltip=\"" + incomingTooltip + "\"" +
      	    ", constraint=\"false\", minlen=\"2\", ltail=\"clusterIncoming\", lhead=\"clusterThis\", label=\"implicitly\"];\n"
      	  } else "") +
      	  // outgoing implicit edge
      	  (if (!outgoingImplicits.isEmpty) {
      	    val n = outgoingImplicits.head
      	    "node" + node2Index(thisNode) + " -> node" + node2Index(n) +
      	    " [id=\"" + cssClass(thisNode, n) + "|" + node2Index(thisNode) + "_" + node2Index(n) + "\", tooltip=\"" + outgoingTooltip + "\"" +
      	    ", constraint=\"false\", minlen=\"2\", ltail=\"clusterThis\", lhead=\"clusterOutgoing\", label=\"implicitly\"];\n"
      	  } else "") +
        "}"
      }
    }

    // assemble graph
    val graph = "digraph G {\n" +
      // graph / node / edge attributes
      graphAttributesStr +
      "node [" + nodeAttributesStr + "];\n" +
      "edge [" + edgeAttributesStr + "];\n" +
      implicitsDot + "\n" +
      // inheritance nodes
      nodes.map(n => node2Dot(n, None)).mkString +
      subClasses.map(n => node2Dot(n, subClassesTooltip)).mkString +
      superClasses.map(n => node2Dot(n, superClassesTooltip)).mkString +
      // inheritance edges
      edges.map{ case (from, tos) => tos.map(to => {
        val id = "graph" + counter + "_" + node2Index(to) + "_" + node2Index(from)
        // the X -> Y edge is inverted twice to keep the diagram flowing the right way
        // that is, an edge from node X to Y will result in a dot instruction nodeY -> nodeX [dir="back"]
        "node" + node2Index(to) + " -> node" + node2Index(from) +
        " [id=\"" + cssClass(to, from) + "|" + id + "\", " +
        "tooltip=\"" + from.name + (if (from.name.endsWith(MultiSuffix)) " are subtypes of " else " is a subtype of ") +
          to.name + "\", dir=\"back\", arrowtail=\"empty\"];\n"
      }).mkString}.mkString +
    "}"

    tDot += System.currentTimeMillis
    DiagramStats.addDotGenerationTime(tDot)

    graph
  }

  /**
   * Generates the dot string of a given node.
   */
  private def node2Dot(node: Node, tooltip: Option[String]) = {

    // escape HTML characters in node names
    def escape(name: String) = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");

    // assemble node attribues in a map
    var attr = scala.collection.mutable.Map[String, String]()

    // link
    node.doctpl match {
      case Some(tpl) => attr += "URL" -> (page.relativeLinkTo(tpl) + "#inheritance-diagram")
      case _ =>
    }

    // tooltip
    tooltip match {
      case Some(text) => attr += "tooltip" -> text
      // show full name where available (instead of TraversableOps[A] show scala.collection.parallel.TraversableOps[A])
      case None if node.tpl.isDefined => attr += "tooltip" -> node.tpl.get.qualifiedName
      case _ =>
    }

    // styles
    if(node.isImplicitNode)
      attr ++= implicitStyle
    else if(node.isOutsideNode)
      attr ++= outsideStyle
    else if(node.isTraitNode)
      attr ++= traitStyle
    else if(node.isClassNode)
      attr ++= classStyle
    else if(node.isObjectNode)
      attr ++= objectStyle
    else
      attr ++= defaultStyle

    // HTML label
    var name = escape(node.name)
    var img = ""
    if(node.isTraitNode)
      img = "trait_diagram.png"
    else if(node.isClassNode)
      img = "class_diagram.png"
    else if(node.isObjectNode)
      img = "object_diagram.png"

    if(!img.equals("")) {
      img = "<TD><IMG SCALE=\"TRUE\" SRC=\"" + settings.outdir.value + "/lib/" + img + "\" /></TD>"
      name = name + " "
    }
    val label = "<<TABLE BORDER=\"0\" CELLBORDER=\"0\">" +
    		       "<TR>" + img + "<TD VALIGN=\"MIDDLE\">" + name + "</TD></TR>" +
    		    "</TABLE>>"

    // dot does not allow to specify a CSS class, therefore
    // set the id to "{class}|{id}", which will be used in
    // the transform method
    val id = "graph" + counter + "_" + node2Index(node)
    attr += ("id" -> (cssClass(node) + "|" + id))

    // return dot string
    "node" + node2Index(node) + " [label=" + label + "," + flatten(attr.toMap) + "];\n"
  }

  /**
   * Returns the CSS class for an edge connecting node1 and node2.
   */
  private def cssClass(node1: Node, node2: Node): String = {
    if (node1.isImplicitNode && node2.isThisNode)
      "implicit-incoming"
    else if (node1.isThisNode && node2.isImplicitNode)
      "implicit-outgoing"
    else
      "inheritance"
  }

  /**
   * Returns the CSS class for a node.
   */
  private def cssClass(node: Node): String =
    if (node.isImplicitNode && incomingImplicitNodes.contains(node))
      "implicit-incoming"
    else if (node.isImplicitNode)
      "implicit-outgoing"
    else if (node.isObjectNode)
      "object"
    else if (node.isThisNode)
      "this" + cssBaseClass(node, "")
    else if (node.isOutsideNode)
      "outside" + cssBaseClass(node, "")
    else
      cssBaseClass(node, "default")

  private def cssBaseClass(node: Node, default: String) =
    if (node.isClassNode)
      " class"
    else if (node.isTraitNode)
      " trait"
    else if (node.isObjectNode)
      " trait"
    else
      default

  /**
   * Calls dot with a given dot string and returns the SVG output.
   */
  private def generateSVG(dotInput: String, template: DocTemplateEntity) = {
    val dotOutput = DiagramGenerator.getDotRunner.feedToDot(dotInput, template)
    var tSVG = -System.currentTimeMillis

    val result = if (dotOutput != null) {
      val src = scala.io.Source.fromString(dotOutput);
      try {
        val cpa = scala.xml.parsing.ConstructingParser.fromSource(src, false)
        val doc = cpa.document()
        if (doc != null)
          transform(doc.docElem)
        else
          NodeSeq.Empty
      } catch {
        case exc =>
          if (settings.docDiagramsDebug.value) {
            settings.printMsg("\n\n**********************************************************************")
            settings.printMsg("Encountered an error while generating page for " + template.qualifiedName)
            settings.printMsg(dotInput.toString.split("\n").mkString("\nDot input:\n\t","\n\t",""))
            settings.printMsg(dotOutput.toString.split("\n").mkString("\nDot output:\n\t","\n\t",""))
            settings.printMsg(exc.getStackTrace.mkString("\nException: " + exc.toString + ":\n\tat ", "\n\tat ",""))
            settings.printMsg("\n\n**********************************************************************")
          } else {
            settings.printMsg("\nThe diagram for " + template.qualifiedName + " could not be created due to an internal error.")
            settings.printMsg("Use " + settings.docDiagramsDebug.name + " for more information and please file this as a bug.")
          }
          NodeSeq.Empty
      }
    } else
      NodeSeq.Empty

    tSVG += System.currentTimeMillis
    DiagramStats.addSvgTime(tSVG)

    result
  }

  /**
   * Transforms the SVG generated by dot:
   * - adds a class attribute to the SVG element
   * - changes the path of the node images from absolute to relative
   * - assigns id and class attributes to nodes and edges
   * - removes title elements
   */
  private def transform(e:scala.xml.Node): scala.xml.Node = e match {
    // add an id and class attribute to the SVG element
    case Elem(prefix, "svg", attribs, scope, child @ _*) => {
      val klass = if (isClassDiagram) "class-diagram" else "package-diagram"
      Elem(prefix, "svg", attribs, scope, child map(x => transform(x)) : _*) %
      new UnprefixedAttribute("id", "graph" + counter, Null) %
      new UnprefixedAttribute("class", klass, Null)
    }
    // change the path of the node images from absolute to relative
    case img @ <image></image> => {
      val href = (img \ "@{http://www.w3.org/1999/xlink}href").toString
      val file = href.substring(href.lastIndexOf("/") + 1, href.size)
      img.asInstanceOf[Elem] %
      new PrefixedAttribute("xlink", "href", pathToLib + file, Null)
    }
    // assign id and class attributes to edges and nodes:
    // the id attribute generated by dot has the format: "{class}|{id}"
    case g @ Elem(prefix, "g", attribs, scope, children @ _*) if (List("edge", "node").contains((g \ "@class").toString)) => {
      val res = new Elem(prefix, "g", attribs, scope, (children map(x => transform(x))): _*)
      val dotId = (g \ "@id").toString
      if (dotId.count(_ == '|') == 1) {
        val Array(klass, id) = dotId.toString.split("\\|")
        res % new UnprefixedAttribute("id", id, Null) %
        new UnprefixedAttribute("class", (g \ "@class").toString + " " + klass, Null)
      }
      else res
    }
    // remove titles
    case <title>{ _* }</title> =>
      scala.xml.Text("")
    // apply recursively
    case Elem(prefix, label, attribs, scope, child @ _*) =>
      Elem(prefix, label, attribs, scope, child map(x => transform(x)) : _*)
    case x => x
  }

  /* graph / node / edge attributes */

  private val graphAttributes: Map[String, String] = Map(
      "compound" -> "true",
      "rankdir" -> "TB"
  )

  private val nodeAttributes = Map(
    "shape" -> "rectangle",
    "style" -> "filled",
    "penwidth" -> "1",
    "margin" -> "0.08,0.01",
    "width" -> "0.0",
    "height" -> "0.0",
    "fontname" -> "Arial",
    "fontsize" -> "10.00"
  )

  private val edgeAttributes = Map(
    "color" -> "#d4d4d4",
    "arrowsize" -> "0.5",
    "fontcolor" -> "#aaaaaa",
    "fontsize" -> "10.00",
    "fontname" -> "Arial"
  )

  private val defaultStyle = Map(
    "color" -> "#ababab",
    "fillcolor" -> "#e1e1e1",
    "fontcolor" -> "#7d7d7d",
    "margin" -> "0.1,0.04"
  )

  private val implicitStyle = Map(
    "color" -> "#ababab",
    "fillcolor" -> "#e1e1e1",
    "fontcolor" -> "#7d7d7d"
  )

  private val outsideStyle = Map(
    "color" -> "#ababab",
    "fillcolor" -> "#e1e1e1",
    "fontcolor" -> "#7d7d7d"
  )

  private val traitStyle = Map(
    "color" -> "#37657D",
    "fillcolor" -> "#498AAD",
    "fontcolor" -> "#ffffff"
  )

  private val classStyle = Map(
    "color" -> "#115F3B",
    "fillcolor" -> "#0A955B",
    "fontcolor" -> "#ffffff"
  )

  private val objectStyle = Map(
    "color" -> "#102966",
    "fillcolor" -> "#3556a7",
    "fontcolor" -> "#ffffff"
  )

  private def flatten(attributes: Map[String, String]) = attributes.map{ case (key, value) => key + "=\"" + value + "\"" }.mkString(", ")

  private val graphAttributesStr = graphAttributes.map{ case (key, value) => key + "=\"" + value + "\";\n" }.mkString
  private val nodeAttributesStr = flatten(nodeAttributes)
  private val edgeAttributesStr = flatten(edgeAttributes)
}