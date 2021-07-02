library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "A set of tools to handle devices that sleep",
        name: "globalDataTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "(none)",
		librarySource:""
)
import java.util.concurrent.* 
import groovy.transform.Field

@Field static ConcurrentHashMap globalDataStorage = new ConcurrentHashMap(64)

@Field static Integer dataRecordFormatVersion = 1
ConcurrentHashMap getDataRecordByProduct()
{
	String manufacturer = 	hubitat.helper.HexUtils.integerToHexString( device.getDataValue("manufacturer").toInteger(), 2)
	String deviceType = 	hubitat.helper.HexUtils.integerToHexString( device.getDataValue("deviceType").toInteger(), 2)
	String deviceID = 		hubitat.helper.HexUtils.integerToHexString( device.getDataValue("deviceId").toInteger(), 2)
	String productKey = "${manufacturer}:${deviceType}:${deviceID}"
	return globalDataStorage.get(productKey, new ConcurrentHashMap())
}

void showglobalDataRecord() {
	ConcurrentHashMap dataRecord = getDataRecordByProduct()
	log.info "Data record in global storage is ${dataRecord}."
}