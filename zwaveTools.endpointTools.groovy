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

/*
Map getDeviceRecord() {
	dataRecordByProductType.get("deviceRecord", new ConcurrentHashMap(8, 0.75, 1)) // from globalDataTools
}
*/

Map getFullEndpointRecord() {
	dataRecordByProductType.get("endpoints", new ConcurrentHashMap(8, 0.75, 1)) // from globalDataTools
}

Map getThisEndpointData(Integer ep) {
	fullEndpointRecord.get((ep ?: 0), [:])
}

List<Integer> getThisEndpointClasses(ep) {
	List<Integer> rValue = getThisEndpointData(ep).get("classes", [])
	
	// If they don't exist and its endpoint 0, create them from the inClusters and secureInClusters data.
	if ( ((Integer)(ep ?: 0) == 0) && (rValue.size() == 0 ) ) {
			rValue = (getDataValue("inClusters")?.split(",").collect{ (Integer) hexStrToUnsignedInt(it) }) + ( getDataValue("secureInClusters")?.split(",").collect{ (Integer) hexStrToUnsignedInt(it)  })
	}
	return rValue
}

Integer getEndpointCount(){
	fullEndpointRecord.findAll({it.key != 0}).size() ?: 0
}

Map<Integer,List> getEndpointNotificationsSupported(ep){
	getThisEndpointData(ep).get("notificationsSupported", [:])
}

List<Integer> getEndpointMetersSupported( Integer ep = null ){
	getThisEndpointData(ep).get("metersSupported", [])
}

// Get the endpoint number for a child device
Integer getChildEndpointNumber(com.hubitat.app.DeviceWrapper thisChild) {
	if (! thisChild) return 0
	thisChild.deviceNetworkId.split("-ep")[-1] as Integer
}


// Get List (possibly multiple) child device for a specific endpoint. Child devices can also be of the form '-ep000' 
// Child devices associated with the root device end in -ep000
List<com.hubitat.app.DeviceWrapper> getChildDeviceListByEndpoint( Integer ep ) {
	childDevices.findAll{ getChildEndpointNumber(it)  == (ep ?: 0) }
}


void sendEventToEndpoints(Map inputs)
{
	Map params = [ event: null , ep: null , addRootToEndpoint0: true , alwaysSend: null ]

	if (! (inputs.every{ k, v ->  params.containsKey(k) } ) ) {
		log.error "Error calling sendEventToEndpoints. Supported parameters are ${params.keySet()}. Function was called with parameters: ${inputs.keySet()}"
		return
	}
	
	params << inputs << [ep: ((params.ep ?: 0) as Integer)]
	
	List<com.hubitat.app.DeviceWrapper> targetDevices = getChildDeviceListByEndpoint(params.ep)
	
	if ((params.ep == 0) && (params.addRootToEndpoint0))  { targetDevices += device }
	
	targetDevices.each {
		if (it.hasAttribute(params.event.name) || params.alwaysSend?.contains(params.event.name) ) { 
			it.sendEvent(params.event) 
		}
	}
}

void sendEventToAll(Map inputs)
{
	Map params = [ event: null , addRootToEndpoint0: true , alwaysSend: null ]

	if (! (inputs.every{ k, v ->  params.containsKey(k) } ) ) {
		log.error "Error calling sendEventToEndpoints. Supported parameters are ${params.keySet()}. Function was called with parameters: ${inputs.keySet()}"
		return
	}
	params << inputs
	(childDevices + device).each {
		if (it.hasAttribute(params.event.name) || params.alwaysSend?.contains(params.event.name) ) { 
			it.sendEvent(params.event) 
		}
	}
}


// Debugging Functions
void showEndpointlDataRecord() {
	// Debugging function - shows the entire concurrent @Field 'global' data record for all devices using a particular driver
	log.debug "Endpoint Data record is ${fullEndpointRecord.inspect()}."
}
