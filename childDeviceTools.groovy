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
		if ((thisDeviceDataRecord.endpoints.containsKey(childNetIdComponents[1] as Integer)) && (childNetIdComponents[0]?.startsWith(device.deviceNetworkId)) ) {
			return
		} else {
			deleteChildDevice(child.deviceNetworkId)
		}			
	}
}
String getChildNetID(ep, index){
	return "${device.deviceNetworkId}.child${index}-ep${"${ep}".padLeft(3, "0") }"
}

void createChildDevices()
{	
	thisDeviceDataRecord.endpoints?.each
	{ ep, endpointRecord ->
		
		endpointRecord.children?.eachWithIndex { thisChildItem, index ->
			String childNetworkId = getChildNetID(ep, index)
			com.hubitat.app.DeviceWrapper cd = getChildDevice(childNetworkId)
			if (cd.is( null )) {
				log.info "Device ${device.displayName}: creating child device: ${childNetworkId} with driver ${thisChildItem.type} and namespace: ${thisChildItem.namespace}."
				
				addChildDevice(thisChildItem.namespace, thisChildItem.type, childNetworkId, [name: thisChildItem.childName ?: childNetworkId, isComponent: false])
			} 
		}
	}
}
/////////////////////////////////////////////////////////////////
