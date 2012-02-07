package edu.washington.cs.knowitall
package pattern

import scala.io.Source
import scala.util.matching.Regex
import scala.collection
import scopt.OptionParser
import edu.washington.cs.knowitall.collection.immutable.Interval
import tool.stem.MorphaStemmer
import tool.parse.graph._
import tool.parse.pattern._
import org.slf4j.LoggerFactory
import edu.washington.cs.knowitall.pattern.lda.Distributions
import edu.washington.cs.knowitall.util.DefaultObjects
import edu.washington.cs.knowitall.extractor.ReVerbExtractor
import edu.washington.cs.knowitall.nlp.OpenNlpSentenceChunker
import edu.washington.cs.knowitall.normalization.RelationString

class Extraction(
    val arg1: String, 
    val rel: String, 
    val relLemmas: Option[Set[String]], 
    val arg2: String) {

  def this(arg1: String, rel: String, arg2: String) = this(arg1, rel, None, arg2)

  override def equals(that: Any) = that match {
    case that: Extraction => (that canEqual this) && that.arg1 == this.arg1 && that.rel == this.rel && that.arg2 == this.arg2
    case _ => false
  }
  def canEqual(that: Any) = that.isInstanceOf[Extraction]
  override def hashCode = arg1.hashCode + 39 * (rel.hashCode + 39 * arg2.hashCode)

  override def toString() = Iterable(arg1, rel, arg2).mkString("(", "; ", ")")

  def replaceRelation(relation: String) = new Extraction(this.arg1, relation, relLemmas, this.arg2)
  def softMatch(that: Extraction) = 
    (that.arg1.contains(this.arg1) || this.arg1.contains(that.arg1)) &&
    this.relLemmas == that.relLemmas &&
    (that.arg2.contains(this.arg2) || this.arg2.contains(that.arg2))
}

case class Template(template: String) {
  import Template._
  def apply(extr: Extraction, m: Match[DependencyNode]) = {
    val rel = group.replaceAllIn(template, (gm: Regex.Match) => m.groups(gm.group(1)).text)

    extr.replaceRelation(rel)
  }
}
object Template {
  val group = """\{(.*?)}""".r
  def deserialize(string: String) = Template(string)
}

abstract class PatternExtractor(val pattern: Pattern[DependencyNode]) {
  
  def extract(dgraph: DependencyGraph)(implicit 
    buildExtraction: (DependencyGraph, Match[DependencyNode])=>Option[Extraction], 
    validMatch: Graph[DependencyNode]=>Match[DependencyNode]=>Boolean): Iterable[Extraction]
  def confidence(extr: Extraction): Double
  def confidence: Option[Double] // independent confidence

  override def toString = pattern.toString
}

class GeneralPatternExtractor(pattern: Pattern[DependencyNode], val patternCount: Int, val maxPatternCount: Int) extends PatternExtractor(pattern) {
  import GeneralPatternExtractor._
  
  def this(pattern: Pattern[DependencyNode], dist: Distributions) = this(pattern, dist.patternCount(dist.patternEncoding(pattern.toString)), dist.maxPatternCount)

  protected def extractWithMatches(dgraph: DependencyGraph)(implicit
    buildExtraction: (DependencyGraph, Match[DependencyNode])=>Option[Extraction], 
    validMatch: Graph[DependencyNode]=>Match[DependencyNode]=>Boolean) = {

    // apply pattern and keep valid matches
    val matches = pattern(dgraph.graph)
    if (!matches.isEmpty) logger.debug("matches: " + matches.mkString(", "))

    val filtered = matches.filter(validMatch(dgraph.graph))
    if (!filtered.isEmpty) logger.debug("filtered: " + filtered.mkString(", "))

    for (m <- filtered; extr <- buildExtraction(dgraph, m)) yield {
      (extr, m)
    }
  }

  override def extract(dgraph: DependencyGraph)(implicit 
    buildExtraction: (DependencyGraph, Match[DependencyNode])=>Option[Extraction], 
    validMatch: Graph[DependencyNode]=>Match[DependencyNode]=>Boolean) = {
    logger.debug("pattern: " + pattern)
    
    val extractions = this.extractWithMatches(dgraph).map(_._1)
    if (!extractions.isEmpty) logger.debug("extractions: " + extractions.mkString(", "))
    
    extractions
  }

  override def confidence(extr: Extraction): Double = {
    this.confidence.get
  }
  
  override def confidence: Option[Double] = 
    Some(patternCount.toDouble / maxPatternCount.toDouble)
}
object GeneralPatternExtractor {
  val logger = LoggerFactory.getLogger(this.getClass)
}

class TemplatePatternExtractor(val template: Template, pattern: Pattern[DependencyNode], patternCount: Int, maxPatternCount: Int) 
extends GeneralPatternExtractor(pattern, patternCount, maxPatternCount) {
  override def extract(dgraph: DependencyGraph)(implicit
    buildExtraction: (DependencyGraph, Match[DependencyNode])=>Option[Extraction], 
    validMatch: Graph[DependencyNode]=>Match[DependencyNode]=>Boolean) = {

    val extractions = super.extractWithMatches(dgraph)

    extractions.map{ case (extr, m) => template(extr, m) }
  }
}

class SpecificPatternExtractor(val relation: String, 
  val relationLemmas: List[String], 
  pattern: Pattern[DependencyNode], patternCount: Int, relationCount: Int) 
extends GeneralPatternExtractor(pattern, patternCount, relationCount) {

  def this(relation: String, 
    pattern: Pattern[DependencyNode], dist: Distributions) =
    this(relation, 
      // todo: hack
      (relation.split(" ").toSet -- PatternExtractor.LEMMA_BLACKLIST).toList,
      pattern, 
      dist.relationByPattern(dist.relationEncoding(relation))._1(dist.patternEncoding(pattern.toString)),
      dist.relationByPattern(dist.relationEncoding(relation))._2)

  override def extract(dgraph: DependencyGraph)(implicit 
    buildExtraction: (DependencyGraph, Match[DependencyNode])=>Option[Extraction], 
    validMatch: Graph[DependencyNode]=>Match[DependencyNode]=>Boolean) = {
    val extractions = super.extract(dgraph)
    extractions.withFilter{ extr =>
      val extrRelationLemmas = extr.rel.split(" ").map(MorphaStemmer.instance.lemmatize(_))
      relationLemmas.forall(extrRelationLemmas.contains(_))
    }.map(_.replaceRelation(relation))
  }
}

class LdaPatternExtractor private (pattern: Pattern[DependencyNode], private val patternCode: Int, val dist: Distributions) 
extends GeneralPatternExtractor(pattern, dist.patternCount(patternCode), dist.patternCount) {
  import LdaPatternExtractor._
  
  def this(pattern: Pattern[DependencyNode], dist: Distributions) = this(pattern, dist.patternEncoding(pattern.toString), dist)

  override def extract(dgraph: DependencyGraph)(implicit 
    buildExtraction: (DependencyGraph, Match[DependencyNode])=>Option[Extraction], 
    validMatch: Graph[DependencyNode]=>Match[DependencyNode]=>Boolean) = {
    val p = dist.patternEncoding(pattern.toString)

    super.extract(dgraph).flatMap { extr =>
      // find relation string that intersects with extraction relation string
      val extrRelationLemmas = extr.rel.split(" ").map(MorphaStemmer.instance.lemmatize(_))
      val rels = dist.relationLemmas.filter{case (relation, lemmas) => extrRelationLemmas.forall(exr => lemmas.contains(exr))}.map(_._1)
      if (!rels.isEmpty) logger.debug("matching relstrings: " + rels.mkString(", "))

      // replace the relation
      if (rels.isEmpty) {
	    logger.debug("extraction discarded, no matching relstrings")
	    None
      }
      else {
	    val bestRel = rels.maxBy(rel => dist.prob(dist.relationEncoding(rel))(p))
        val replaced = extr.replaceRelation(bestRel)
        logger.debug("replaced extraction: " + replaced)
        Some(replaced)
      }
    }
  }

  override def confidence(extr: Extraction) = {
    val r = dist.relationEncoding(extr.rel)
    dist.prob(r)(patternCode)
  }
  
  // the confidence is not independent of the extraction
  override def confidence: Option[Double] = None
}
object LdaPatternExtractor {
  val logger = LoggerFactory.getLogger(this.getClass)
}

object PatternExtractor {
  val LEMMA_BLACKLIST = Set("for", "in", "than", "up", "as", "to", "at", "on", "by", "with", "from", "be", "like", "of")
  val VALID_ARG_POSTAG = Set("NN", "NNS", "NNP", "NNPS", "JJ", "JJS", "CD", "PRP")
  val logger = LoggerFactory.getLogger(this.getClass)
  
  def confidence(extr: Extraction, count: Int, maxCount: Int): Double = {
    count.toDouble / maxCount.toDouble
  }
  
  private def validMatch(restrictArguments: Boolean)(graph: Graph[DependencyNode])(m: Match[DependencyNode]) = {
    // no neighboring neg edges
    !m.bipath.nodes.exists { v =>
      graph.edges(v).exists(_.label == "neg")
	} && 
	(!restrictArguments || (VALID_ARG_POSTAG.contains(m.nodeGroups("arg1").node.postag) && VALID_ARG_POSTAG.contains(m.nodeGroups("arg2").node.postag)))
  }

  private def buildExtraction(expandArgument: Boolean)(graph: DependencyGraph, m: Match[DependencyNode]): Option[Extraction] = {
    val groups = m.nodeGroups
  
    val rel = groups.get("rel").map(_.node) getOrElse(throw new IllegalArgumentException("no rel: " + m))
    val arg1 = groups.get("arg1").map(_.node) getOrElse(throw new IllegalArgumentException("no arg1: " + m)) 
    val arg2 = groups.get("arg2").map(_.node) getOrElse(throw new IllegalArgumentException("no arg2: " + m))
    
    def neighborsUntil (node: DependencyNode, inferiors: List[DependencyNode], until: Set[DependencyNode]) = {
      val lefts = inferiors.takeWhile(_ != node).reverse
      val rights = inferiors.dropWhile(_ != node).drop(1)

      val indices = Interval.span(node.indices :: lefts.takeWhile(!until(_)).map(_.indices) ++ rights.takeWhile(!until(_)).map(_.indices))
      
      // use the original dependencies nodes in case some information
      // was lost.  For example, of is collapsed into the edge prep_of
      graph.nodes.filter(node => node.indices.max >= indices.min && node.indices.max <= indices.max)
    }
    
    def expandAdjacent(node: DependencyNode, until: Set[DependencyNode], labels: Set[String]) = {
      def takeAdjacent(interval: Interval, nodes: List[DependencyNode], pool: List[DependencyNode]): List[DependencyNode] = pool match {
        // can we add the top node?
        case head :: tail if (head.indices borders interval) && !until.contains(head) => 
          takeAdjacent(interval union head.indices, head :: nodes, tail)
        // otherwise abort
        case _ => nodes
      }
      
      // it might be possible to simply have an adjacency restriction
      // in this condition
      def cond(e: Graph.Edge[DependencyNode]) = 
        labels.contains(e.label)
      val inferiors = graph.graph.inferiors(node, cond).toList.sortBy(_.indices)
      
      // split into nodes left and right of node
      val lefts = inferiors.takeWhile(_ != node).reverse
      val rights = inferiors.dropWhile(_ != node).drop(1)
      
      // take adjacent nodes from each list
      val withLefts = takeAdjacent(node.indices, List(node), lefts)
      val expanded = takeAdjacent(node.indices, withLefts, rights)
      
      expanded.sortBy(_.indices)
    }
    
    def expand(node: DependencyNode, until: Set[DependencyNode], labels: Set[String]) = {
      // don't restrict to adjacent (by interval) because prep_of, etc.
      // remove some nodes that we want to expand across.  In the end,
      // we get the span over the inferiors.
      def cond(e: Graph.Edge[DependencyNode]) = 
        labels.contains(e.label)
      val inferiors = graph.graph.inferiors(node, cond).toList.sortBy(_.indices)
      neighborsUntil(node, inferiors, until)
    }
    
    /**
     *  Return the components next to the node.
     *  @param  node  components will be found adjacent to this node
     *  @param  labels  components may be connected by edges with any of these labels
     *  @param  without  components may not include any of these nodes
     **/
    def components(node: DependencyNode, labels: Set[String], without: Set[DependencyNode]) = {
      // nodes across an allowed label to a subcomponent
      val across = graph.graph.neighbors(node, (dedge: DirectedEdge[_]) => dedge.dir match {
        case Direction.Down if labels.contains(dedge.edge.label) => true
        case _ => false
      })
      
      val components = across.flatMap { start =>
        // get inferiors without passing back to node
        val inferiors = graph.graph.inferiors(start, 
            (e: Graph.Edge[DependencyNode]) => 
              // make sure we don't cycle out of the component
              e.dest != node && 
              // make sure we don't descend into another component
              // i.e. "John M. Synge who came to us with his play direct
              // from the Aran Islands , where the material for most of
              // his later works was gathered"
              !labels.contains(e.label))
            
        // make sure none of the without nodes are in the component
        if (without.forall(!inferiors.contains(_))) {
          val span = Interval.span(inferiors.map(_.indices).toSeq)
          Some(graph.nodes.filter(node => span.superset(node.indices)))
        }
        else None
      }
      
      // TODO: remove first toList.  This is a hack that resolves a NPE
      // because some node has a null postag.
      components.flatten.toList
    }

    def simpleExpandNoun(node: DependencyNode, until: Set[DependencyNode]) = {
      val labels = 
        Set("det", "prep_of", "amod", "num", "nn", "poss", "quantmod")
      
      val expansion = expand(node, until, labels)
      if (expansion.exists(_.isProperNoun)) expansion.sortBy(_.indices)
      else (expansion ++ components(node, Set("rcmod", "infmod", "partmod", "ref"), until)).sortBy(_.indices)
    }

    def relationExpandNoun(node: DependencyNode, until: Set[DependencyNode]) = {
      val labels = 
        Set("det", "amod", "num", "nn", "poss", "quantmod")
      expand(node, until, labels)
    }
    
    /**
     * Expand over adjacent advmod edges.
     */
    def relationExpandVerb(node: DependencyNode, until: Set[DependencyNode]) = {
      expandAdjacent(node, until, Set("advmod"))
    }
    
    def expandRelation(node: DependencyNode) = {
      if (node.isNoun) relationExpandNoun(node, Set(arg1, arg2))
      else if (node.isVerb) relationExpandVerb(node, Set(arg1, arg2))
      else List(node)
    }

    def makeString(nodes: List[DependencyNode]) = nodes.map(_.text).mkString(" ")
    
    val expandedArg1 = if (expandArgument) simpleExpandNoun(arg1, Set(rel)) else List(arg1)
    val expandedArg2 = if (expandArgument) simpleExpandNoun(arg2, Set(rel)) else List(arg2)
    val expandedRel = if (expandArgument) expandRelation(rel) else List(rel)
    if (Interval.span(expandedArg1.map(_.indices)) intersects Interval.span(expandedArg2.map(_.indices))) {
      logger.debug("invalid: arguments overlap: " + makeString(expandedArg1) + ", " + makeString(expandedArg2))
      None
    }
    else Some(new Extraction(makeString(expandedArg1), makeString(expandedRel), Some(makeString(expandedRel).split(" ").map(MorphaStemmer.instance.lemmatize(_)).toSet -- PatternExtractor.LEMMA_BLACKLIST), makeString(expandedArg2)))
  }

  implicit def implicitBuildExtraction = this.buildExtraction(true)_
  implicit def implicitValidMatch = this.validMatch(false) _

  def loadGeneralExtractorsFromFile(patternFilePath: String): List[GeneralPatternExtractor] = {
    val patternSource = Source.fromFile(patternFilePath)
    val patterns: List[(Pattern[DependencyNode], Int)] = try {
      // parse the file
      patternSource.getLines.map { line =>
        line.split("\t") match {
          // full information specified
          case Array(pat, count) => (DependencyPattern.deserialize(pat), count.toInt)
          // assume a count of 1 if nothing is specified
          case Array(pat) => logger.warn("warning: pattern has no count: " + pat); (DependencyPattern.deserialize(pat), 1)
          case _ => throw new IllegalArgumentException("file can't have more than two columns")
        }
      }.toList
    } finally {
      patternSource.close
    }

    val maxCount = patterns.maxBy(_._2)._2
    (for ((p, count) <- patterns) yield {
      new GeneralPatternExtractor(p, count, maxCount)
    }).toList
  }

  def loadTemplateExtractorsFromFile(patternFilePath: String): List[GeneralPatternExtractor] = {
    val patternSource = Source.fromFile(patternFilePath)
    val patterns: List[(Template, Pattern[DependencyNode], Int)] = try {
      // parse the file
      patternSource.getLines.map { line =>
        line.split("\t") match {
          // full information specified
          case Array(template, pat, count) => 
            (Template.deserialize(template), DependencyPattern.deserialize(pat), count.toInt)
          // assume a count of 1 if nothing is specified
          case Array(template, pat) => 
            logger.warn("warning: pattern has no count: " + pat); 
            (Template.deserialize(template), DependencyPattern.deserialize(pat), 1)
          case _ => throw new IllegalArgumentException("file can't have more than two columns")
        }
      }.toList
    } finally {
      patternSource.close
    }

    val maxCount = patterns.maxBy(_._3)._3
    (for ((template, pattern, count) <- patterns) yield {
      new TemplatePatternExtractor(template, pattern, count, maxCount)
    }).toList
  }
  
  def loadLdaExtractorsFromDistributions(dist: Distributions): List[LdaPatternExtractor] = {
    (for (p <- dist.patternCodes) yield {
      new LdaPatternExtractor(DependencyPattern.deserialize(dist.patternDecoding(p)), dist)
    }).toList
  }

  def loadGeneralExtractorsFromDistributions(dist: Distributions): List[GeneralPatternExtractor] = {
    (for (p <- dist.patternCodes) yield {
      new GeneralPatternExtractor(DependencyPattern.deserialize(dist.patternDecoding(p)), dist)
    }).toList
  }

  def loadSpecificExtractorsFromDistributions(dist: Distributions): List[GeneralPatternExtractor] = {
    (for (p <- dist.patternCodes; 
      val pattern = DependencyPattern.deserialize(dist.patternDecoding(p));
      r <- dist.relationsForPattern(p)) yield {
      new SpecificPatternExtractor(dist.relationDecoding(r),
        pattern, 
        dist)
    }).toList
  }
  
  def main(args: Array[String]) {
    val parser = new OptionParser("applypat") {
      var patternFilePath: Option[String] = None
      var ldaDirectoryPath: Option[String] = None
      
      var sentenceFilePath: String = null
      var extractorType: String = null
      
      var confidenceThreshold = 0.0;

      var showReverb: Boolean = false
      var duplicates: Boolean = false
      var expandArguments: Boolean = false
      var showAll: Boolean = false
      var verbose: Boolean = false
      var collapseVB: Boolean = false

      opt(Some("p"), "patterns", "<file>", "pattern file", { v: String => patternFilePath = Option(v) })
      opt(None, "lda", "<directory>", "lda directory", { v: String => ldaDirectoryPath = Option(v) })
      doubleOpt(Some("t"), "threshold", "<threshold>", "confident threshold for shown extractions", { t: Double => confidenceThreshold = t })

      opt("d", "duplicates", "keep duplicate extractions", { duplicates = true })
      opt("x", "expand-arguments", "expand extraction arguments", { expandArguments = true })
      opt("r", "reverb", "show which extractions are reverb extractions", { showReverb = true })
      opt("collapse-vb", "collapse 'VB.*' to 'VB' in the graph", { collapseVB = true })

      opt("a", "all", "don't restrict extractions to are noun or adjective arguments", { showAll = true })
      opt("v", "verbose", "", { verbose = true })

      arg("type", "type of extractor", { v: String => extractorType = v })
      arg("sentences", "sentence file", { v: String => sentenceFilePath = v })
    }
    
    if (parser.parse(args)) {
      // optionally load the distributions
      val distributions = parser.ldaDirectoryPath.map {
        logger.info("loading distributions")
        Distributions.fromDirectory(_)
      }
      
      logger.info("reading patterns")
      // sort by inverse count so frequent patterns appear first 
      val extractors: List[PatternExtractor] = ((parser.extractorType, distributions) match {
        case ("lda", Some(distributions)) => loadLdaExtractorsFromDistributions(distributions)
        case ("general", Some(distributions)) => loadGeneralExtractorsFromDistributions(distributions)
        case ("template", None) => 
          loadTemplateExtractorsFromFile(
            parser.patternFilePath.getOrElse(
              throw new IllegalArgumentException("pattern template file (--patterns) required for the template extractor.")))
        case ("specific", Some(distributions)) => loadSpecificExtractorsFromDistributions(distributions)
        case ("general", None) => 
          loadGeneralExtractorsFromFile(
            parser.patternFilePath.getOrElse(
              throw new IllegalArgumentException("pattern file (--patterns) required for the general extractor.")))
        case _ => throw new IllegalArgumentException("invalid parameters")
      }).toList
      
      /*
      logger.info("building reverse lookup")
      val reverseLookup = (for (extractor <- extractors; edge <- extractor.pattern.edgeMatchers.collect{case m: DependencyEdgeMatcher => m}) yield {
        (edge.label, extractor)
      }).foldLeft(Map[String, List[PatternExtractor]]().withDefaultValue(List())) { (acc, pair) => 
        acc + (pair._1 -> (pair._2 :: acc(pair._1))))
      }
      */

      implicit def implicitBuildExtraction = this.buildExtraction(parser.expandArguments)_
      implicit def implicitValidMatch = this.validMatch(!parser.showAll)_
      
      case class Result(conf: Double, extr: Extraction, text: String) extends Ordered[Result] {
        override def toString = text
        
        override def compare(that: Result) = {
          val conf = this.conf.compare(that.conf)

          if (conf == 0) (this.extr.toString + text).compareTo(that.extr.toString + text)
          else conf
        }
      }
      
      val chunker = if (parser.showReverb) Some(new OpenNlpSentenceChunker) else None
      val reverb = if (parser.showReverb) Some(new ReVerbExtractor) else None
      
      def reverbExtract(sentence: String) = {
        import scala.collection.JavaConversions._
        val chunked = chunker.get.chunkSentence(sentence)
        val extractions = reverb.get.extract(chunked)
        extractions.map { extr =>
          val rs = new RelationString(extr.getRelation.getText, extr.getRelation.getTokens.map(MorphaStemmer.instance.lemmatize(_)).mkString(" "), extr.getRelation.getPosTags.mkString(" "))
          rs.correctNormalization()
          
          new Extraction(extr.getArgument1.getText, extr.getRelation.getText, Some(rs.getNormPred.split(" ").toSet -- PatternExtractor.LEMMA_BLACKLIST), extr.getArgument2.getText)
        }
      }
      
      logger.info("performing extractions")
      val sentenceSource = Source.fromFile(parser.sentenceFilePath)
      try {
        for (line <- sentenceSource.getLines) {
          val parts = line.split("\t")
          require(parts.length <= 2, "each line in sentence file must have no more than two columns: " + line)

          try {
            val pickled = parts.last
            val text = if (parts.length > 1) Some(parts(0)) else None

            val rawDgraph = DependencyGraph.deserialize(pickled)
            val dgraph = if (parser.collapseVB) rawDgraph.simplifyPostags.simplifyVBPostags
            else rawDgraph.simplifyPostags

            if (text.isDefined) logger.debug("text: " + text.get)
            logger.debug("graph: " + dgraph.serialize)

            if (parser.verbose) {
              if (text.isDefined) println("text: " + text.get)
              println("deps: " + dgraph.serialize)
            }
            
            require(!parser.showReverb || text.isDefined, "original sentence text required to show reverb extractions")
            val reverbExtractions = if (!parser.showReverb) Nil else reverbExtract(text.get)
            if (parser.showReverb) {
              if (parser.verbose) println("reverb: " + reverbExtractions.mkString("[", "; ", "]"))
              logger.debug("reverb: " + reverbExtractions.mkString("[", "; ", "]"))
            }
            
            def confidenceOverThreshold(extractor: PatternExtractor, threshold: Double) = {
              extractor.confidence match { 
                // there is an independent confidence so do the check
                case Some(conf) => conf >= parser.confidenceThreshold
                // there is no independent confidence, so we need to continue
                // and compare the dependent confidence
                case None => true 
              }
            }
            
            /**
              * Quick checks to see if an extraction is possible.  This
              * is an optimization, so the checks should be considerably
              * faster than running the extractors.
              */
            def possibleExtraction(extractor: PatternExtractor, dgraph: DependencyGraph) = {
              extractor.pattern.edgeMatchers.forall { matcher => 
                dgraph.dependencies.exists(matcher.canMatch(_))
              }
            }
            
            val results = for {
              extractor <- extractors;
              // todo: organize patterns by a reverse-lookup on edges
              
              // optimizations
              if (confidenceOverThreshold(extractor, parser.confidenceThreshold));
              if (possibleExtraction(extractor, dgraph));
              
              // extraction
              extr <- extractor.extract(dgraph);
              val conf = extractor.confidence(extr);
              if conf >= parser.confidenceThreshold
          } yield {
              val reverbMatches = reverbExtractions.find(_.softMatch(extr))
              logger.debug("reverb match: " + reverbMatches.toString)
              val extra = reverbMatches.map("\treverb:" + _.toString)

              val resultText =
                if (parser.verbose) "extraction: "+("%1.6f" format conf)+" " + extr.toString + " with (" + extractor.pattern.toString + ")" + reverbMatches.map(" compared to " + _).getOrElse("")
                else ("%1.6f" format conf) + "\t" + extr + "\t" + extractor.pattern + "\t" + ("" /: text)((_, s) => s + "\t") + pickled + extra.getOrElse("")
              Result(conf, extr, resultText)
            }
            
            if (parser.duplicates) {
              for (result <- results.sorted(Ordering[Result].reverse)) {
                println(result)
              }
            }
            else {
              val maxes = for (results <- results.groupBy(_.extr)) yield (results._2.max)
              for (result <- maxes.toSeq.sorted(Ordering[Result].reverse)) {
                println(result)
              }
            }

            if (parser.verbose) println()
          }
          catch {
            case e: DependencyGraph.SerializationException => logger.error("could not deserialize graph.", e)
          }
        }
      } finally {
        sentenceSource.close
      }
    }
  }
}
