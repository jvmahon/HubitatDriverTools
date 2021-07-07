library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Tools to manage device parameters and other device-specific data.",
        name: "parameterManagementTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "",
		librarySource:"https://github.com/jvmahon/HubitatDriverTools/blob/main/deviceManagementTools.groovy"
)

import java.util.concurrent.* 
import groovy.transform.Field


//////////////////////////////////////////////////////////////////////
//////                  Report Queues                          ///////
//////////////////////////////////////////////////////////////////////
// reportQueues stores a map of SynchronousQueues. When requesting configuration information from a device, the report handler communicates the report back to the requesting function using a queue. This makes programming more like "synchronous" programming, rather than asynchronous handling.
// This is a map within a map. The first level map is by deviceNetworkId. Since @Field static variables are shared among all devices using the same driver code, this ensures that you get a unique second-level map for a particular device. The second map is keyed by the report class hex string. For example, if you want to wait for the configurationGet report, wait for "7006".
@Field static reportQueues = new ConcurrentHashMap<String, ConcurrentHashMap>(128)

SynchronousQueue myReportQueue(String reportClass) {
	ConcurrentHashMap thisDeviceQueues = reportQueues.get(device.deviceNetworkId, new ConcurrentHashMap<String,SynchronousQueue>())
	SynchronousQueue thisReportQueue = thisDeviceQueues.get(reportClass, new SynchronousQueue())
	return thisReportQueue
}


/////////////////////////////////////////////////////////////////////////////////////// 
///////                   Parameter Updating and Management                    ////////
///////      Handle Update(), and Set, Get, and Process Parameter Values       ////////
/////////////////////////////////////////////////////////////////////////////////////// 

@Field static  ConcurrentHashMap<String, ConcurrentHashMap> allPendingParameterChanges = new ConcurrentHashMap<String, ConcurrentHashMap>()
@Field static  ConcurrentHashMap<String, ConcurrentHashMap> allDevicesParameterValues = new ConcurrentHashMap<String, ConcurrentHashMap>()

ConcurrentHashMap getParameterRecord() {
	dataRecordByNetworkId.get("parameters", new ConcurrentHashMap()) // from globalDataTools
}

ConcurrentHashMap getPendingChanges(){
	parameterRecord.get("pendingChanges", new ConcurrentHashMap())
}

ConcurrentHashMap getAllParameterCharacteristicRecords(){
	parameterRecord.get("parameterCharacteristics", new ConcurrentHashMap())
}

ConcurrentHashMap setThisParameterCharacteristics(Map params = [number: null , format: 0 , size: null , minValue: null , maxValue: null , defaultValue: null , name: null , detailedInfo: null , readOnly: false , advanced: false, options: null  ]) {
	Map allowedKeys = ["number", "format", "size", "minValue", "maxValue", "defaultValue", "name", "detailedInfo", "readOnly", "advanced", "options" ]
	if ( ! params.keySet.every{k, v ->  allowedKeys.contains(k)}) log.warn "In function setThisParameterCharacteristics: An invalid parameter name was used. Allowed parameters are ${allowedKeys}"
	// number, format, size, minValue, maxValue, defaultValue, advanced, readOnly are from Configuration Properties Report (SDS13781 section 4.32.8)
	// format may be 0-4 (See SDS13781 section 4.32.8 Table 33)
	// Name is intended to hold the name value as if received via ConfigurationNameReport (SDS13781 Section 4.32.4)
	// detailedInfo is the detailed description from ConfiguratinoInfoReport (SDS13781 section 4.32.6)
	log.warn "setThisParameterCharacteristics should be changed to a merge operation"
	allParameterCharacteristicRecords.put(params.number, params)
}

void processPendingChanges()
{
	if (txtEnable) log.info "Device ${device.displayName}: Processing Pending parameter changes: ${getPendingChangeMap()}"
		pendingChanges?.each{ k, v ->
			if (txtEnable) log.info "Updating parameter ${k} to value ${v}"
			setParameter(parameterNumber: k , value: v)
		}
}

void setParameter(parameterNumber, value = null ) {
	if (parameterNumber && ( ! value.is( null) )) {
		setParameter(parameterNumber:parameterNumber, value:value)
	} else if (parameterNumber) {
		sendUnsupervised( zwave.configurationV1.configurationGet(parameterNumber: parameterNumber))
		hubitat.zwave.Command report = myReportQueue("7006").poll(5, TimeUnit.SECONDS)
		if (logEnable) log.debug "Device ${device.displayName}: Received a parameter configuration report: ${report}."
	}
}

Boolean setParameter(Map params = [parameterNumber: null , value: null ] ){
    if (params.parameterNumber.is( null ) || params.value.is( null ) ) {
		log.warn "Device ${device.displayName}: Can't set parameter ${parameterNumber}, Incomplete parameter list supplied... syntax: setParameter(parameterNumber,size,value), received: setParameter(${parameterNumber}, ${size}, ${value})."
		return false
    } 
	
	String getThis = "${params.parameterNumber}" as String

	Integer PSize = ( deviceInputs.get(getThis)?.size) ?: (deviceInputs.get(params.parameterNumber as Integer)?.size )
	
	if (!PSize) {log.error "Device ${device.displayName}: Could not get parameter size in function setParameter. Defaulting to 1"; PSize = 1}

	sendUnsupervised(zwave.configurationV1.configurationSet(scaledConfigurationValue: params.value as BigInteger, parameterNumber: params.parameterNumber, size: PSize))
	// The 'get' should not be supervised!
	sendUnsupervised( zwave.configurationV1.configurationGet(parameterNumber: params.parameterNumber))
	
	// Wait for the report that is returned after the configurationGet, and then update the input controls so they display the updated value.
	Boolean success = myReportQueue("7006").poll(5, TimeUnit.SECONDS) ? true : false 

}

Map<Integer, BigInteger> getParameterValuesFromDevice()
{
	ConcurrentHashMap parameterValues = allDevicesParameterValues.get(device.deviceNetworkId, new ConcurrentHashMap<Integer, BigInteger>(32))
	
	ConcurrentHashMap inputs = getDeviceInputs()	
	
	log.debug "In function getParameterValuesFromDevice, parameter values are: ${parameterValues}. Size is: ${parameterValues.size()}. Inputs size is ${inputs.size()}."
	
	if (!inputs) return null

	if ((parameterValues?.size() as Integer) == (inputs?.size() as Integer) ) 
	{
		// if (logEnable) log.debug "Device ${device.displayName}: In Function getParameterValuesFromDevice, returning Previously retrieved Parameter values: ${parameterValues}"

		return parameterValues
	} else {
		// if (logEnable) log.debug "Getting missing parameter values"
		Integer waitTime = 1
		inputs.eachWithIndex 
			{ k, v, i ->
				if (parameterValues.get(k as Integer).is( null ) ) {
					if (txtEnable) log.info "Device ${device.displayName}: Obtaining value from Zwave device for parameter # ${k}"
					sendUnsupervised(zwave.configurationV2.configurationGet(parameterNumber: k))
						// Wait 2 second for most of the reports, but wait up to 10 seconds for the last one.
						waitTime = (i >= (inputs.size() -1 )) ? 10 : 2
						myReportQueue("7006").poll(waitTime, TimeUnit.SECONDS)

				} else {
					if (logEnable) log.debug "Device ${device.displayName}: For parameter: ${k} previously retrieved a value of ${parameterValues.get(k as Integer)}."
				}
			}
		return parameterValues			
	}
	return null
}

void zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationReport  cmd)
{ 
	if (logEnable) log.debug "Device ${device.displayName}: Received a configuration report ${cmd}."
	ConcurrentHashMap parameterValues = allDevicesParameterValues.get(device.deviceNetworkId, new ConcurrentHashMap<Integer, BigInteger>(32))
	BigInteger newValue = (cmd.size == 1) ? cmd.configurationValue[0] : cmd.scaledConfigurationValue			
	if (newValue < 0) log.warn "Device ${device.displayName}: Negative configuration value reported for configuration parameter ${cmd.parameterNumber}."
				
	parameterValues.put((cmd.parameterNumber as Integer), newValue )
	
	pendingChangeMap.remove(cmd.parameterNumber as Integer)
	
	if (txtEnable) log.info "Device ${device.displayName}: updating parameter: ${cmd.parameterNumber} to ${newValue}."
	device.updateSetting("${cmd.parameterNumber}", newValue as Integer)
		
	myReportQueue(cmd.CMD).offer( cmd )
}