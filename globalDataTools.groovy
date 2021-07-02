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

@Field static ConcurrentHashMap globalDataStorage = new ConcurrentHashMap(64)

@Field static Integer dataRecordFormatVersion = 1

ConcurrentHashMap getDataRecordByProductLine()
{
	String manufacturer = 	hubitat.helper.HexUtils.integerToHexString( device.getDataValue("manufacturer").toInteger(), 2)
	String deviceType = 	hubitat.helper.HexUtils.integerToHexString( device.getDataValue("deviceType").toInteger(), 2)
	String deviceID = 		hubitat.helper.HexUtils.integerToHexString( device.getDataValue("deviceId").toInteger(), 2)
	String productKey = "${manufacturer}:${deviceType}:${deviceID}"
	return globalDataStorage.get(productKey, new ConcurrentHashMap())
}

void showglobalDataRecord() {
	// Debugging function - shows the entire concurrent @Field 'global' data record for all devices using a particular driver
	ConcurrentHashMap dataRecord = getDataRecordByProductLine()
	log.info "Data record in global storage is ${dataRecord.inspect()}."
}