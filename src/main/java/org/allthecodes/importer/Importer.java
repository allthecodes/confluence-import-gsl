package org.allthecodes.importer;

import java.io.File;
import java.io.FileFilter;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;

import org.jglue.fluentjson.JsonBuilderFactory;
import org.jglue.fluentjson.JsonObjectBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Whitelist;

import com.google.gson.JsonObject;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;


public class Importer {
	
	
	private static String targetSpace = "";
	
	private static String confluenceHost = "";
	
	private static String confluenceUser = "";
	
	private static String confluencePassword = "";
	
	private static String sourceDirectory = "";
	
	private static final String SPACER = "  \u2003";
	
	private static Set<String> rollbackIds = new LinkedHashSet<String>();
	
	public static void main(String args[]) throws Exception
	{
		
		try
		{
			File directory = new File(sourceDirectory);
			processDirectory(directory, "");
		}
		catch (Exception e)
		{
			e.printStackTrace();
			System.err.println("Error Encountered - Rolling Back Migration");
			doRollback();
		}
		
		
	}
	
	public static void doRollback() throws Exception
	{
		for (String id : rollbackIds)
		{
			Unirest.delete(confluenceHost + "/rest/api/content/" + id)
			.basicAuth(confluenceUser, confluencePassword)
			.header("Content-Type", "application/json")
			.asJson();
			System.err.println("Deleteted Content Id: " + id);
		}
	}
	
	public static String processFile(File f, String directoryName, String parentId) throws Exception
	{
		String content = new String(Files.readAllBytes(f.toPath()));
		
		System.err.println("Processing " + directoryName);
		
		if (!content.isEmpty() || content.equals("") || content == null)
		{
			try
			{
				//content = Jsoup.clean(content, Whitelist.basicWithImages());
				Whitelist w = Whitelist.relaxed();
				//w.removeTags("div", "table");
				content = Jsoup.clean(content, w);
				
				Document d = Jsoup.parse(content);

				d.outputSettings().syntax(Document.OutputSettings.Syntax.xml);

				content = d.html();
				
			}
			catch (Exception e)
			{
				e.printStackTrace();
			}
		}
		
		String safeDocumentName = findSafeDocumentName(directoryName);
		
		String jsonString = Importer.buildJsonObject(content, safeDocumentName.toLowerCase(), parentId);
		
		
		HttpResponse<JsonNode> response = Unirest.post(confluenceHost + "/rest/api/content/")
			.basicAuth(confluenceUser, confluencePassword)
			.header("Content-Type", "application/json")
			.body(jsonString)
			.asJson();
				
		String json = response.getBody().toString();
		
		if (response.getStatus() != 200)
		{
			System.err.println(response.getBody());
		}
		
		Object document = Configuration.defaultConfiguration().jsonProvider().parse(json);
		
		String id = JsonPath.read(document, "$.id");
		rollbackIds.add(id);
		
		return id;
		
	}
	
	private static String findSafeDocumentName(String directoryName) throws Exception {
		
		HttpResponse<JsonNode> existanceCheck = Unirest.get(confluenceHost + "/rest/api/content/")
				.basicAuth(confluenceUser, confluencePassword)
				.queryString("title", directoryName.toLowerCase())
				.queryString("spaceKey", targetSpace)
				.asJson();

		Object document = Configuration.defaultConfiguration().jsonProvider().parse(existanceCheck.getBody().toString());
		
		Integer size = JsonPath.read(document, "$.size");
		if (size == 0)
		{
			return directoryName;
		}
		else
		{
			return findSafeDocumentName(directoryName + SPACER);
		}
		
	}

	public static void processDirectory(File directory, String parentId) throws Exception
	{
		File[] files = directory.listFiles();
		String directoryName = directory.getName(); 
		
		// process the index, then process the child directories
		
		File[] indexFile = directory.listFiles(new FileFilter() {
			
			@Override
			public boolean accept(File pathname) {
				if (pathname.isFile() && pathname.getName().equals("index.html"))
					return true;
				return false;
			}
		});
		
		String id = "";
		if (indexFile.length > 0)
		{
			id = Importer.processFile(indexFile[0], "" + directoryName, parentId);
		}
		
		files = directory.listFiles(new FileFilter() {
			public boolean accept(File pathname) {
				if (pathname.isDirectory())
					return true;
				return false;
			}
			
		});
		
		
		for (File f : files)
		{
			processDirectory(f, id);
		}
	}
	
	public static String buildJsonObject (String content, String directoryName, String parentId)
	{
		
		JsonObjectBuilder builder = JsonBuilderFactory.buildObject()
				.add("type", "page")
				.add("title", directoryName.toLowerCase())
				
				.addObject("space")
					.add("key", targetSpace).end()
				.addObject("body")
					.addObject("storage")
					.add("value", content)
					.add("representation", "storage")
					.end()
				 .end();
				
		if (parentId != null && !parentId.isEmpty())
		{
			builder.addArray("ancestors")
				.addObject()
				.add("id", parentId)
	          .end();
	        
		}
		
		
		return builder.getJson().toString();
	}
	
}
