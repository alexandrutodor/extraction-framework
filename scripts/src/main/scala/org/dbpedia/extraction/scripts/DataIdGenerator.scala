package org.dbpedia.extraction.scripts

import java.io._
import java.net.URI
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.logging.{Level, Logger}

import com.hp.hpl.jena.rdf.model.{Model, ModelFactory, Resource}
import com.hp.hpl.jena.vocabulary.RDF
import org.apache.jena.atlas.json.{JSON, JsonObject}
import org.dbpedia.extraction.destinations.{DBpediaDatasets, Dataset}
import org.dbpedia.extraction.util.Language

import scala.Console._
import scala.collection.JavaConverters._
import scala.io.{BufferedSource, Source}
import scala.reflect.runtime.currentMirror
import scala.reflect.runtime.universe._


/**
 * Created by Chile on 1/8/2016.
 */
object DataIdGenerator {


  val dateformat = new SimpleDateFormat("yyyy-MM-dd")
  def main(args: Array[String]) {

    require(args != null && args.length >= 1,
      "need three args: " +
        /*0*/ "config file location"
    )

    val source = scala.io.Source.fromFile(args(0))
    val jsonString = source.mkString.replaceAll("#.*", "")
    source.close()

    val configMap = JSON.parse(jsonString)
    var uri: Resource = null
    var dataset: Resource = null
    var topset: Resource = null

    val logger = Logger.getLogger(getClass.getName)

    // Collect arguments
    val webDir = configMap.get("webDir").getAsString.value() + (if(configMap.get("webDir").getAsString.value().endsWith("/")) "" else "/")
    require(URI.create(webDir) != null, "Please specify a valid web directory!")


    val dump = new File(configMap.get("localDir").getAsString.value)
    require(dump.isDirectory() && dump.canRead(), "Please specify a valid local dump directory!")

    //not required
    val lbp = try {Source.fromFile(configMap.get("linesBytesPacked").getAsString.value)} catch{ case fnf : FileNotFoundException => null case f : BufferedSource => f}
    val lbpMap = Option(lbp) match {
      case Some(ld) => ld.getLines.map(_.split(";")).map(x => x(0) -> Map("lines" -> x(1), "bytes" -> x(2), "bz2" -> x(3))).toMap
      case None => Map[String,Map[String, String]]()
    }

    val compression = configMap.get("fileExtension").getAsString.value
    require(compression.startsWith("."), "please provide a valid file extension starting with a dot")

    val extensions = configMap.get("serializations").getAsArray.subList(0,configMap.get("serializations").getAsArray.size()).asScala
    require(extensions.map(x => x.getAsString.value().startsWith(".")).foldLeft(true)(_ && _), "list of valid serialization extensions starting with a dot")

     require(!configMap.get("outputFileTemplate").getAsString.value.contains("."), "Please specify a valid output file name without extension")

    val dbpVersion = configMap.get("dbpediaVersion").getAsString.value
    val idVersion = configMap.get("dataidVersion").getAsString.value
    val vocabulary = configMap.get("vocabularyUri").getAsString.value
    require(URI.create(vocabulary) != null, "Please enter a valid ontology uri of ths DBpedia release")

    val license = configMap.get("licenseUri").getAsString.value
    require(URI.create(license) != null, "Please enter a valid license uri (odrl license)")

    val r = currentMirror.reflect(DBpediaDatasets)
    val datasetDescriptionsOriginal = r.symbol.typeSignature.members.toStream
      .collect{case s : TermSymbol if !s.isMethod => r.reflectField(s)}
      .map(t => t.get match {
        case y : Dataset => y
        case _ =>
      }).toList.asInstanceOf[List[Dataset]]

    val datasetDescriptions = datasetDescriptionsOriginal
      .map(d => new Dataset(d.name.replace("_", "-"), d.description)) ++ datasetDescriptionsOriginal
      .filter(_.name.endsWith("unredirected"))
      .map(d => new Dataset(d.name.replace("_unredirected", "").replace("_", "-"), d.description + " This dataset has Wikipedia redirects resolved.")) ++ datasetDescriptionsOriginal
      .map(d => new Dataset(d.name.replace(d.name, d.name + "-en-uris").replace("_", "-"), d.description + " Normalized resources matching English DBpedia.")) ++ datasetDescriptionsOriginal
      .map(d => new Dataset(d.name.replace(d.name, d.name + "-en-uris-unredirected").replace("_", "-"), d.description + " Normalized resources matching English DBpedia. This dataset has Wikipedia redirects resolved.")).sortBy(x => x.name)

    def addPrefixes(model: Model): Unit =
    {
      model.setNsPrefix("dataid", "http://dataid.dbpedia.org/ns/core#")
      model.setNsPrefix("dc", "http://purl.org/dc/terms/")
      model.setNsPrefix("dcat", "http://www.w3.org/ns/dcat#")
      model.setNsPrefix("void", "http://rdfs.org/ns/void#")
      model.setNsPrefix("prov", "http://www.w3.org/ns/prov#")
      model.setNsPrefix("xsd", "http://www.w3.org/2001/XMLSchema#")
      model.setNsPrefix("owl", "http://www.w3.org/2002/07/owl#")
      model.setNsPrefix("foaf", "http://xmlns.com/foaf/0.1/")
      model.setNsPrefix("xsd", "http://www.w3.org/2001/XMLSchema#")
      model.setNsPrefix("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#")
      model.setNsPrefix("rdfs", "http://www.w3.org/2000/01/rdf-schema#")
      model.setNsPrefix("dmp", "http://dataid.dbpedia.org/ns/dmp#")
    }

    def addAgent(model: Model, lang: Language, agentMap: JsonObject): Resource =
    {
      val agent = model.createResource(agentMap.get("uri").getAsString.value())
      model.add(agent, RDF.`type`, model.createResource(model.getNsPrefixURI("dataid") + "Agent"))
      model.add(agent, model.createProperty(model.getNsPrefixURI("foaf"), "name"), model.createLiteral(agentMap.get("name").getAsString.value()))
      if(agentMap.get("homepage") != null)
        model.add(agent, model.createProperty(model.getNsPrefixURI("foaf"), "homepage"), model.createResource(agentMap.get("homepage").getAsString.value()))
      model.add(agent, model.createProperty(model.getNsPrefixURI("foaf"), "mbox"), model.createLiteral(agentMap.get("mbox").getAsString.value()))

      Option(lang) match{
        case Some(lang) =>{
          val context = model.createResource(webDir + lang.wikiCode + "/dataid.ttl?subj=" + agentMap.get("role").getAsString.value().toLowerCase + "Context")
          model.add(context, RDF.`type`, model.createResource(model.getNsPrefixURI("dataid") + "AuthorityEntityContext"))
          model.add(context, model.createProperty(model.getNsPrefixURI("dataid"), "authorizedAgent"), agent)
          model.add(context, model.createProperty(model.getNsPrefixURI("dataid"), "authorityAgentRole"), model.createResource(model.getNsPrefixURI("dataid") + agentMap.get("role").getAsString.value()))
          model.add(context, model.createProperty(model.getNsPrefixURI("dataid"), "isInheritable"), model.createTypedLiteral("true", model.getNsPrefixURI("xsd") + "boolean" ))
          model.add(context, model.createProperty(model.getNsPrefixURI("dataid"), "authorizedFor"), uri)
        }
        case None =>
      }
      agent
    }

    //creating a dcat:Catalog pointing to all DataIds
    val catalogModel = ModelFactory.createDefaultModel()
    addPrefixes(catalogModel)

    val catalogAgent = addAgent(catalogModel, null, configMap.get("creator").getAsObject)

    val catalog = catalogModel.createResource(webDir + dbpVersion + "_dataid_catalog.ttl")
    catalogModel.add(catalog, RDF.`type`, catalogModel.createResource(catalogModel.getNsPrefixURI("dcat") + "Catalog"))
    catalogModel.add(catalog, catalogModel.createProperty(catalogModel.getNsPrefixURI("dc"), "title"), catalogModel.createLiteral("DataId catalog for DBpedia version " + dbpVersion))
    catalogModel.add(catalog, catalogModel.createProperty(catalogModel.getNsPrefixURI("rdfs"), "label"), catalogModel.createLiteral("DataId catalog for DBpedia version " + dbpVersion))
    catalogModel.add(catalog, catalogModel.createProperty(catalogModel.getNsPrefixURI("dc"), "description"), catalogModel.createLiteral("DataId catalog for DBpedia version " + dbpVersion + ". Every DataId represents a language dataset of DBpedia.", "en"))
    catalogModel.add(catalog, catalogModel.createProperty(catalogModel.getNsPrefixURI("dc"), "modified"), catalogModel.createTypedLiteral(dateformat.format(new Date()), catalogModel.getNsPrefixURI("xsd") + "date"))
    catalogModel.add(catalog, catalogModel.createProperty(catalogModel.getNsPrefixURI("dc"), "issued"), catalogModel.createTypedLiteral(dateformat.format(new Date()), catalogModel.getNsPrefixURI("xsd") + "date"))
    catalogModel.add(catalog, catalogModel.createProperty(catalogModel.getNsPrefixURI("dc"), "publisher"), catalogAgent)
    catalogModel.add(catalog, catalogModel.createProperty(catalogModel.getNsPrefixURI("dc"), "license"), catalogModel.createResource(license))
    catalogModel.add(catalog, catalogModel.createProperty(catalogModel.getNsPrefixURI("foaf"), "homepage"), catalogModel.createResource(configMap.get("creator").getAsObject.get("homepage").getAsString.value()))

    //visit all subdirectories, determain if its a dbpedia language dir, and create a DataID for this language
    for(outer <- dump.listFiles().filter(_.isDirectory))
    {
      for(dir <- outer.listFiles().filter(_.isDirectory))
      {
        val lang = Language.get(dir.getName.replace("_", "-")) match{
          case Some(l) => l
          case _ => {
            logger.log(Level.INFO, "no language found for: " + dir.getName)
            null
          }
        }
        val filterstring = ("^[^$]+_" + dir.getName + "(" + extensions.foldLeft(new StringBuilder){ (sb, s) => sb.append("|" + s.getAsString.value()) }.toString.substring(1) + ")" + compression).replace(".", "\\.")
        val filter = new FilenameFilter {
          override def accept(dir: File, name: String): Boolean = {
            if(name.matches(filterstring))
              return true
            else
              return false
          }
        }

        val distributions = dir.listFiles(filter).map(x => x.getName).toList.sorted

        if(lang != null && distributions.map(x => x.contains("infobox-properties")).foldRight(false)(_ || _)) {

          val subModel = ModelFactory.createDefaultModel()
          val model = ModelFactory.createDefaultModel()

          addPrefixes(subModel)
          addPrefixes(model)

          val outfile = new File(dir.getAbsolutePath.replace("\\", "/") + "/" + configMap.get("outputFileTemplate").getAsString.value + "_" + lang.wikiCode + ".ttl")

          uri = subModel.createResource(webDir + outer.getName + "/" + lang.wikiCode + "/" + configMap.get("outputFileTemplate").getAsString.value + "_" + lang.wikiCode + ".ttl")
          require(uri != null, "Please provide a valid directory")
          subModel.add(uri, RDF.`type`, subModel.createResource(subModel.getNsPrefixURI("dataid") + "DataId"))

          val creator = addAgent(subModel, lang, configMap.get("creator").getAsObject)
          val maintainer = addAgent(subModel, lang, configMap.get("maintainer").getAsObject)
          val contact = addAgent(subModel, lang, configMap.get("contact").getAsObject)
          require(creator != null, "Please define an dataid:Agent as a Creator in the dataid stump file (use AuthorityEntityContext).")

          subModel.add(uri, subModel.createProperty(subModel.getNsPrefixURI("dc"), "modified"), subModel.createTypedLiteral(dateformat.format(new Date()), subModel.getNsPrefixURI("xsd") + "date"))
          subModel.add(uri, subModel.createProperty(subModel.getNsPrefixURI("dc"), "issued"), subModel.createTypedLiteral(dateformat.format(new Date()), subModel.getNsPrefixURI("xsd") + "date"))
          //TODO subModel.add(uri, subModel.createProperty(subModel.getNsPrefixURI("dc"), "hasVersion"), subModel.createLiteral(idVersion))
          subModel.add(uri, subModel.createProperty(subModel.getNsPrefixURI("dataid"), "hasAccessLevel"), subModel.createResource(subModel.getNsPrefixURI("dataid") + "PublicAccess"))
          subModel.add(uri, subModel.createProperty(subModel.getNsPrefixURI("dataid"), "latestVersion"), uri)
          subModel.add(uri, subModel.createProperty(subModel.getNsPrefixURI("dataid"), "associatedAgent"), creator)
          subModel.add(uri, subModel.createProperty(subModel.getNsPrefixURI("dataid"), "associatedAgent"), maintainer)
          subModel.add(uri, subModel.createProperty(subModel.getNsPrefixURI("dataid"), "associatedAgent"), contact)
          catalogModel.add(catalog, catalogModel.createProperty(catalogModel.getNsPrefixURI("dcat"), "record"), uri)

          addDataset(subModel, lang, "dataset", creator, true)
          topset = dataset

          subModel.add(uri, subModel.createProperty(subModel.getNsPrefixURI("foaf"), "primaryTopic"), topset)
          subModel.add(topset, subModel.createProperty(subModel.getNsPrefixURI("void"), "vocabulary"), subModel.createResource(vocabulary))
          subModel.add(topset, subModel.createProperty(subModel.getNsPrefixURI("dc"), "description"), subModel.createLiteral(configMap.get("description").getAsString.value, "en"))

          if ((configMap.get("addDmpProps").getAsBoolean.value()))
            addDmpStatements(subModel, topset)

          var lastFile: String = null
          for (dis <- distributions) {
            if (lastFile != dis.substring(0, dis.lastIndexOf("_"))) {
              lastFile = dis.substring(0, dis.lastIndexOf("_"))
              addDataset(model, lang, dis, creator)
              subModel.add(topset, model.createProperty(model.getNsPrefixURI("void"), "subset"), dataset)
            }
            addDistribution(model, lang, outer.getName, dis, creator)
          }

          //TODO validate & publish DataIds online!!!

          subModel.write(new FileOutputStream(outfile), "TURTLE")
          val baos = new ByteArrayOutputStream()
          model.write(baos, "TURTLE")
          var outString = new String(baos.toByteArray(), Charset.defaultCharset())
          outString = outString.replaceAll("(@prefix).*\\n", "")
          val os = new FileOutputStream(outfile, true)
          val printStream = new PrintStream(os)
          printStream.print(outString)
          printStream.close()
          logger.log(Level.INFO, "finished DataId: " + outfile.getAbsolutePath)
        }
      }
    }

    //write catalog
    catalogModel.write(new FileOutputStream(new File(dump + "/" + dbpVersion + "_dataid_catalog.ttl")), "TURTLE")

    def addDmpStatements(model: Model, dataset: Resource): Unit =
    {
      model.add(dataset, model.createProperty(model.getNsPrefixURI("dmp"), "usefulness"), model.createLiteral(configMap.get("dmpusefulness").getAsString.value, "en"))
      model.add(dataset, model.createProperty(model.getNsPrefixURI("dmp"), "similarData"), model.createLiteral(configMap.get("dmpsimilarData").getAsString.value, "en"))
      model.add(dataset, model.createProperty(model.getNsPrefixURI("dmp"), "reuseAndIntegration"), model.createLiteral(configMap.get("dmpreuseAndIntegration").getAsString.value, "en"))
      model.add(dataset, model.createProperty(model.getNsPrefixURI("dmp"), "additionalSoftware"), model.createLiteral(configMap.get("dmpadditionalSoftware").getAsString.value, "en"))
      model.add(dataset, model.createProperty(model.getNsPrefixURI("dmp"), "repositoryUrl"), model.createResource(configMap.get("dmprepositoryUrl").getAsString.value))
      model.add(dataset, model.createProperty(model.getNsPrefixURI("dmp"), "growth"), model.createLiteral(configMap.get("dmpgrowth").getAsString.value, "en"))
      model.add(dataset, model.createProperty(model.getNsPrefixURI("dmp"), "archiveLink"), model.createResource(configMap.get("dmparchiveLink").getAsString.value))
      model.add(dataset, model.createProperty(model.getNsPrefixURI("dmp"), "preservation"), model.createLiteral(configMap.get("dmppreservation").getAsString.value, "en"))
      model.add(dataset, model.createProperty(model.getNsPrefixURI("dmp"), "openness"), model.createLiteral(configMap.get("dmpopenness").getAsString.value, "en"))
    }

    def addDataset(model: Model, lang: Language, currentFile: String, associatedAgent: Resource, toplevelSet: Boolean = false): Unit =
    {
      val datasetName = if(currentFile.contains("_")) currentFile.substring(0, currentFile.indexOf("_")) else currentFile
      dataset = model.createResource(uri.getURI + "?set=" + datasetName)
      model.add(dataset, RDF.`type`, model.createResource(model.getNsPrefixURI("dataid") + "Dataset"))
      if(!toplevelSet) //not!
      {
        model.add(dataset, model.createProperty(model.getNsPrefixURI("void"), "rootResource"), topset)

        datasetDescriptions.find(x => x.name == datasetName && x.description != null) match
        {
          case Some(d) => model.add(dataset, model.createProperty(model.getNsPrefixURI("dc"), "description"), model.createLiteral(d.description, "en"))
          case None => err.println("Could not find description for dataset: " + lang.wikiCode + "/" + currentFile)
        }

      }
      model.add(dataset, model.createProperty(model.getNsPrefixURI("dc"), "title"), model.createLiteral("DBpedia " + dbpVersion + " " + datasetName.substring(datasetName.lastIndexOf("/") +1) + (if(lang != null) {" " + lang.wikiCode} else "") + " dump dataset", "en"))
      model.add(dataset, model.createProperty(model.getNsPrefixURI("rdfs"), "label"), model.createLiteral(datasetName.substring(datasetName.lastIndexOf("/") +1) + (if(lang != null) {"_" + lang.wikiCode} else "") + "_" + dbpVersion, "en"))
      model.add(dataset, model.createProperty(model.getNsPrefixURI("dcat"), "landingPage"), model.createResource("http://dbpedia.org/"))
      //TODO done by DataId Hub
      //TODO model.add(dataset, model.createProperty(model.getNsPrefixURI("dc"), "hasVersion"), model.createLiteral(idVersion))
      //TODO model.add(dataset, model.createProperty(model.getNsPrefixURI("dataid"), "latestVersion"), dataset)
      model.add(dataset, model.createProperty(model.getNsPrefixURI("dataid"), "hasAccessLevel"), model.createResource(model.getNsPrefixURI("dataid") + "PublicAccess"))
      model.add(dataset, model.createProperty(model.getNsPrefixURI("dataid"), "associatedAgent"), associatedAgent)
      model.add(dataset, model.createProperty(model.getNsPrefixURI("dc"), "modified"), model.createTypedLiteral(dateformat.format(new Date()), model.getNsPrefixURI("xsd") + "date") )
      model.add(dataset, model.createProperty(model.getNsPrefixURI("dc"), "issued"), model.createTypedLiteral(dateformat.format(new Date()), model.getNsPrefixURI("xsd") + "date") )
      model.add(dataset, model.createProperty(model.getNsPrefixURI("dc"), "license"), model.createResource(license))
      model.add(dataset, model.createProperty(model.getNsPrefixURI("dcat"), "keyword"), model.createLiteral("DBpedia", "en"))
      model.add(dataset, model.createProperty(model.getNsPrefixURI("dcat"), "keyword"), model.createLiteral(datasetName, "en"))
      if(lang.iso639_3 != null && lang.iso639_3.length > 0)
        model.add(dataset, model.createProperty(model.getNsPrefixURI("dc"), "language"), model.createResource("http://lexvo.org/id/iso639-3/" + lang.iso639_3))

      lbpMap.get(("core-i18n/" + lang.wikiCode.replace("-", "_") + "/" + currentFile).replace(compression, "")) match {
        case Some(triples) =>
          model.add(dataset, model.createProperty(model.getNsPrefixURI("void"), "triples"), model.createTypedLiteral((new Integer(triples.get("lines").get) -2), model.getNsPrefixURI("xsd") + "integer") )
        case None =>
      }

    }


    def addDistribution(model: Model, lang: Language, outerDirectory: String, currentFile: String, associatedAgent: Resource): Unit =
    {
      val dist = model.createResource(uri.getURI + "?file=" + currentFile)
      model.add(dist, RDF.`type`, model.createResource(model.getNsPrefixURI("dataid") + "SingleFile"))
      model.add(dataset, model.createProperty(model.getNsPrefixURI("dcat"), "distribution"), dist)
      model.add(dist, model.createProperty(model.getNsPrefixURI("dc"), "title"), model.createLiteral("DBpedia " + dbpVersion + " " + currentFile + (if(lang != null) {" " + lang.wikiCode} else "") + " dump dataset", "en"))

      datasetDescriptions.find(x => x.name == currentFile.substring(0, currentFile.lastIndexOf("_")) && x.description != null) match
      {
        case Some(d) => model.add(dist, model.createProperty(model.getNsPrefixURI("dc"), "description"), model.createLiteral(d.description, "en"))
        case None => err.println("Could not find description for distribution: " + lang.wikiCode + " / " + currentFile)
      }

      model.add(dist, model.createProperty(model.getNsPrefixURI("rdfs"), "label"), model.createLiteral(currentFile + (if(lang != null) {"_" + lang.wikiCode} else "") + "_" + dbpVersion, "en"))
      //TODO done by DataId Hub
      //TODO model.add(dist, model.createProperty(model.getNsPrefixURI("dc"), "hasVersion"), model.createLiteral(idVersion))
      //TODO model.add(dist, model.createProperty(model.getNsPrefixURI("dataid"), "latestVersion"), dist)
      model.add(dist, model.createProperty(model.getNsPrefixURI("dataid"), "hasAccessLevel"), model.createResource(model.getNsPrefixURI("dataid") + "PublicAccess"))
      model.add(dist, model.createProperty(model.getNsPrefixURI("dataid"), "associatedAgent"), associatedAgent)
      model.add(dist, model.createProperty(model.getNsPrefixURI("dc"), "modified"), model.createTypedLiteral(dateformat.format(new Date()), model.getNsPrefixURI("xsd") + "date") )
      model.add(dist, model.createProperty(model.getNsPrefixURI("dc"), "issued"), model.createTypedLiteral(dateformat.format(new Date()), model.getNsPrefixURI("xsd") + "date") )
      model.add(dist, model.createProperty(model.getNsPrefixURI("dc"), "license"), model.createResource(license))

      lbpMap.get((outerDirectory + "/" + lang.wikiCode + "/" + currentFile).replace(compression, "")) match {
        case Some(bytes) =>
          model.add(dist, model.createProperty(model.getNsPrefixURI("dcat"), "byteSize"), model.createTypedLiteral(bytes.get(("bz2")).get, model.getNsPrefixURI("xsd") + "integer") )
        case None =>
      }
      model.add(dist, model.createProperty(model.getNsPrefixURI("dcat"), "downloadURL"), model.createResource(webDir + outerDirectory + "/" + lang.wikiCode.replace("-", "_") + "/" + currentFile))
      model.add(dist, model.createProperty(model.getNsPrefixURI("dcat"), "mediaType"), model.createLiteral(if(compression.contains("gz")) "application/x-gzip" else if(compression.contains("bz2")) "application/x-bzip2" else ""))
      val postfix = dist.getURI.substring(dist.getURI.lastIndexOf("_"))
      model.add(dist, model.createProperty(model.getNsPrefixURI("dcat"), "format"), model.createLiteral(if(postfix.contains(".ttl")) "text/turtle" else if(postfix.contains(".tql") || postfix.contains(".nq")) "application/n-quads" else if(postfix.contains(".nt")) "application/n-triples" else ""))
    }
  }
}
