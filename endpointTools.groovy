library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Endpoint Support Functions",
        name: "endpointTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "(child creation depends on the thisDeviceDataRecord data record - can this be removed?)",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/endpointTools.groovy"
)

//////////////////////////////////////////////////////////////////////
//////        Endpoint / Child Device  Handling Methods        ///////
// These methods assume all chld devices have a network ID that ends in the format '-ep###' where ### is the endpoint number
// For example, AF-ep001 would be endpoint #1 for the parent device "AF"
////////////////////////////////////////////////////////////////////// 

// Get the endpoint number for a child device
Integer getEndpoint(com.hubitat.app.DeviceWrapper thisDevice) {
	return thisDevice?.deviceNetworkId.split("-ep")[-1] as Integer
}

// Get child device for a particular endpoint. The following function
// is used when an endpoint can have one and only one child device.
com.hubitat.app.DeviceWrapper getTargetDeviceByEndPoint(ep = null ) {
	if (ep) { 
		return getChildDevices().find{ it -> (getEndpoint(it) == (ep as Integer))}
	} else { 
		return device 
	}
}

// Use when an endpoint can have more than one child device. An endpoint may have multiple child devices where, for example, the device is a multi-sensor and each sensor element is to be represented as its own device.
// child devices can also be of the form '-ep000" which means its a child device for the root.
// In that case, an additional index may be desired. For example, if you want to represent a multi-sensor as a
// Collection of component devices, you might use component network IDs like "99.child0-ep000, 99.child1-ep000"
List<com.hubitat.app.DeviceWrapper> getTargetDeviceListByEndPoint(ep = null ) {

	List<com.hubitat.app.DeviceWrapper> returnList
	returnList	= getChildDevices().findAll{ it -> (getEndpoint(it)  == ((ep ?: 0) as Integer))}
	if ( (ep ?: 0) == 0 ) returnList += device 
	return returnList
}

List<com.hubitat.app.DeviceWrapper> getRootAndAllChildDevices(){
	return getChildDevices() + device
}

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
		log.debug "endpoint ${ep}, index ${index} has thisChildItem is ${thisChildItem}"
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
