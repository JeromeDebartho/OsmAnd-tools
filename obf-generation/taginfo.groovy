def builder = new groovy.json.JsonBuilder()
def json = [:]
json["data_format"] = 1;
json["data_url"] = "http://builder.osmand.net:8080/view/WebSite/job/OsmAndTagInfo/ws/taginfo.json"
json["data_updated"] = new Date().format("yyyyMMdd'T'hhmmssZ") 
json["project"] = [
	"name": "OsmAnd",
	"description": "OsmAnd Maps & Navigation",
	"project_url": "http://osmand.net",
	"icon_url": "https://raw.githubusercontent.com/osmandapp/OsmAnd-misc/master/logo/simple/osmand-app-72.png",
	"contact_name": "OsmAnd Team",
	"contact_email": "contactus@osmand.net"
];
json["tags"] = [:]
def txt = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(json));
println txt
new File("taginfo.json").text = txt
