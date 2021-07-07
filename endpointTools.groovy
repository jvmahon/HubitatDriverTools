library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Tools to Manage an Endpoint Data Record Functions",
        name: "endpointTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "zwaveTools.globalDataTools",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/endpointTools.groovy"
)
/*

		classVersions:[44:1, 89:1, 37:1, 0:1, 122:1, 133:2, 112:1, 134:2, 114:1, 115:1, 90:1, 91:3, 50:3, 94:1, 32:1, 43:1], 

		endpoints:[
				0:[ children:[[type:'Generic Component Motion Sensor', 'namespace':'hubitat', childName:"Motion Sensor"]],
					classes:[80, 85, 89, 90, 94, 108, 112, 113, 114, 115, 122, 128, 133, 134, 135, 142, 159], 
					notificationsSupported:[7:[3, 8], 8:[1, 5], 9:[4, 5] ],
					metersSupported:[0, 2, 4, 5], 
				],
				
*/
Map getFullEndpointRecord() {
	dataRecordByProductType.get("endpoints", new ConcurrentHashMap()) // from globalDataTools
}

Map getThisEndpointData(ep) {
	fullEndpointRecord.get((ep ?: 0 as Integer))
}

Map getThisEndpointClasses(ep) {
	thisEndpointData(ep).get("classes", new ConcurrentHashMap())
}

Integer getEndpointCount(){
	fullEndpointRecord.findAll({it.key != 0}).size()
}

Map<Integer,List> getEndpointNotificationsSupported(ep){
	thisEndpointData(ep).get("notificationsSupported")
}

List<Integer> getEndpointMetersSupported(ep){
	thisEndpointData(ep).get("metersSupported")
}

// Get the endpoint number for a child device
Integer getChildEndpointNumber(com.hubitat.app.DeviceWrapper thisChild) {
	thisChild?.deviceNetworkId.split("-ep")[-1] as Integer
}

// Get List (possibly multiple) child device for a specific endpoint. Child devices can also be of the form '-ep000' 
// Child devices associated with the root device end in -ep000
List<com.hubitat.app.DeviceWrapper> getChildDeviceListByEndpoint(ep) {

	List<com.hubitat.app.DeviceWrapper> returnList
	returnList	= getChildDevices().findAll{ it -> (getEndpoint(it)  == ((ep ?: 0) as Integer))}
	if ( (ep ?: 0) == 0 ) returnList += device 
	return returnList
}

// Debugging Functions
void showEndpointlDataRecord() {
	// Debugging function - shows the entire concurrent @Field 'global' data record for all devices using a particular driver

	log.debug "Endpoint Data record is ${fullEndpointRecord.inspect()}."
}
