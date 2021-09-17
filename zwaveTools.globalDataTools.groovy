library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "A set of tools to set up and manage data stored in a global field.",
        name: "globalDataTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "none",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/globalDataTools.groovy"
)

import java.util.concurrent.* 
import groovy.transform.Field

@Field static ConcurrentHashMap globalDataStorage = new ConcurrentHashMap(64, 0.75, 1)

@Field static Integer dataRecordFormatVersion = 1

ConcurrentHashMap getDataRecordByProductType()
{
	String manufacturer = 	hubitat.helper.HexUtils.integerToHexString( device.getDataValue("manufacturer").toInteger(), 2)
	String deviceType = 	hubitat.helper.HexUtils.integerToHexString( device.getDataValue("deviceType").toInteger(), 2)
	String deviceID = 		hubitat.helper.HexUtils.integerToHexString( device.getDataValue("deviceId").toInteger(), 2)
	String productKey = "${manufacturer}:${deviceType}:${deviceID}"
	ConcurrentHashMap dataRecord = globalDataStorage.get(productKey, new ConcurrentHashMap<String,ConcurrentHashMap>(8, 0.75, 1))
	return dataRecord
}

ConcurrentHashMap getDataRecordByNetworkId()
{
	return globalDataStorage.get(deviceNetworkId, new ConcurrentHashMap<String,ConcurrentHashMap>())
}

// Debugging Functions
void showGlobalDataRecordByProductType() {
	// Debugging function - shows the entire concurrent @Field 'global' data record for all devices using a particular driver

	log.debug "Data record in global storage is ${dataRecordByProductType.inspect()}."
}

void showFullGlobalDataRecord() {
	// Debugging function - shows the entire concurrent @Field 'global' data record for all devices using a particular driver
	log.debug "Global Data Record Is ${globalDataStorage.inspect()}."
}