library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Child device Support Functions",
        name: "childDeviceTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "child creation depends on the thisDeviceDataRecord data record - can this be removed?",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/endpointTools.groovy"
)

/////////////////////////////////////////////////////////////////////////
//////        Create and Manage Child Devices for Endpoints       ///////
/////////////////////////////////////////////////////////////////////////

void deleteUnwantedChildDevices()
{	
	// Delete child devices that don't use the proper network ID form (parent ID, followed by "-ep" followed by endpoint number).
	getChildDevices()?.each
	{ child ->	
	
		List childNetIdComponents = child.deviceNetworkId.split("-ep")
		if ((getFullEndpointRecord().containsKey(childNetIdComponents[1] as Integer)) && (childNetIdComponents[0]?.startsWith(device.deviceNetworkId)) ) {
			return
		} else {
			deleteChildDevice(child.deviceNetworkId)
		}			
	}
}


void createChildDevices()
{	
	getFullEndpointRecord()?.each
	{ ep, endpointRecord ->
		
		endpointRecord.children?.eachWithIndex { thisChildItem, index ->
			String childNetworkId = getChildNetID(ep, index)
			com.hubitat.app.DeviceWrapper cd = getChildDevice(childNetworkId)
			if (cd.is( null )) {
				log.info "Device ${device.displayName}: creating child device: ${childNetworkId} with driver ${thisChildItem.type} and namespace: ${thisChildItem.namespace}."
				
				addChildDevice(thisChildItem.namespace, thisChildItem.type, childNetworkId, [name: device.displayName, isComponent: false])
			} 
		}
	}
}
//
		command "addNewChildDevice", [[name:"Device Name*", type:"STRING"], 
                                      [name:"componentDriverName*",type:"ENUM", constraints:(getDriverChoices()) ], 
                                      [name:"Endpoint*",type:"NUMBER", description:"Endpoint Number, Use 0 for root (parent) device" ] ]

//

List getDriverChoices() {
	// Returns the name of the generic component drivers with their namespace listed in parenthesis
    // log.debug getInstalledDrivers().findAll{it.name.toLowerCase().startsWith("generic component")}.collect{ "${it.name} (${it.namespace})"}.sort()
    return getInstalledDrivers().findAll{it.name.toLowerCase().startsWith("generic component")}.collect{ "${it.name} (${it.namespace})"}.sort()
}

String getChildNetID(Integer ep, Integer index){
	return "${device.deviceNetworkId}.child${index}-ep${"${ep}".padLeft(3, "0") }"
}

String getNextChild(Integer ep)
{
	Integer thisChild = 1
	String rValue = getChildNetID(ep, thisChild)

	while ( getChildDevice(rValue) ) { 
			thisChild ++ 
			rValue = getChildNetID(ep, thisChild)
		}
	return rValue
}
void addNewChildDevice(String newChildName, String componentDriverName, endpoint) {
	// log.debug "Driver name is ${newChildName} with driver component type ${componentDriverName} for endpoint ${endpoint}"
	Map thisDriver = getInstalledDrivers().find{ "${it.name} (${it.namespace})" == componentDriverName }

	// log.debug "selected driver is ${thisDriver}"
	String thisChildNetworkId = getNextChild((int) (endpoint ?: 0))
	addChildDevice(thisDriver.namespace, thisDriver.name, thisChildNetworkId, [name: newChildName, isComponent: false])
}

/////////////////////////////////////////////////////////////////
