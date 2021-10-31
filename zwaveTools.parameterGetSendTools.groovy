library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Tools to manage device parameters and other device-specific data.",
        name: "parameterGetSendTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "",
		librarySource:"https://github.com/jvmahon/HubitatDriverTools/blob/main/parameterManagementTools.groovy"
)

import java.util.concurrent.* 
import groovy.transform.Field

/*
[
minValue: // See ConfigurationPropertiesReport 
maxValue: // See ConfigurationPropertiesReport 
defaultValue: // See ConfigurationPropertiesReport 
size: // See ConfigurationPropertiesReport 
format:  // See ConfigurationPropertiesReport 
readOnly:true 	// Custom! 
title: 			// Corresponds to ConfigurationNameReport name
description: 	// Corresponds to ConfigurationNameReport info 
]
*/

ConcurrentHashMap getParameterStorageMap() {
	getDataRecordByNetworkId().get("cachedParameters", new ConcurrentHashMap<Integer, Integer>(8,0.75,1))
}

void showParameterStorageMap(){
	if (logEnable) log.debug "Parameter storage map is: ${parameterStorageMap}"
}

Map<Integer, Map> getAllParameterCharacteristics(){
	return getThisDeviceDatabaseRecord()?.deviceInputs
}

Map getInputs(){
	if (getUserDefinedParameterInputs){
		getUserDefinedParameterInputs()
	} else {
		allParameterCharacteristics
	}
}


Map parameterCharacteristics(Integer parameter) {
	allParameterCharacteristics.get((int) parameter)
}

Integer getParameterSize(parameterNumber) {
	Integer rValue = parameterCharacteristics((int) parameterNumber).size
	return rValue
}

List<Integer> getListOfParameters(){
	allParameterCharacteristics.collect{k, v -> (int) k}
}

Map getAllParameterValues(){
	getListedParameterValues(listOfParameters)
}


Boolean parameterRangeValid( inputs = [parameterNumber: null , value: null ]) {
	Map thisParameter = parameterCharacteristics((int) inputs.parameterNumber)
	
	if (thisParameter.maxValue && ((int) thisParameter.maxValue < (int) inputs.value)) {
		log.warn "For parameter ${inputs.parameterNumber}, value ${inputs.value} exceeds maximum permitted value of ${thisParameter.maxValue}"
		return false
		}
	if (thisParameter.minValue && ((int) thisParameter.minValue > (int) inputs.value)) {
		log.warn "For parameter ${inputs.parameterNumber}, value ${inputs.value} is below minimum permitted value of ${thisParameter.minValue}"
		return false
		}
		
	return true
}

Map getListedParameterValues(List<Integer> parameterList, Boolean useCache = true ) {
	parameterList?.collectEntries{ it ->
			Integer newValue = getParameterValue(parameterNumber:((int) it), useCache:(useCache), waitTime:2)
			[(it):(newValue)]
		}
}

void zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationReport  cmd)
{ 
	Integer newValue = (cmd.size == 1) ? cmd.configurationValue[0] : cmd.scaledConfigurationValue			
	if (newValue < 0) log.warn "Device ${device.displayName}: Negative configuration value reported for configuration parameter ${cmd.parameterNumber}."

	parameterStorageMap.put((cmd.parameterNumber as Integer), newValue )
		
	myReportQueue(cmd.CMD).offer( cmd )
	(childDevices + this).each{ if (it.handleParameterReport) it.handleParameterReport(cmd)}
}

void getParameter(number){
	advancedZwaveSend( zwave.configurationV1.configurationGet(parameterNumber: (int) number))

}

Integer getParameterValue(params = [:]) {

	Map inputs = [parameterNumber: null , useCache: true , waitTime: 4] << params
	if (inputs.parameterNumber.is( null ))  {
			log.error "In function getParameterValue, no parameter number was specified. Returning null"
			return null
		}
	Integer rValue = null
	if (inputs.useCache as Boolean) rValue = getParameterStorageMap()?.get((int) inputs.parameterNumber)

	if (logEnable) "Obtained parameter ${inputs.parameterNumber} from cache. Value is ${rValue}."
	
	Integer attempt = 1
	while(rValue.is( null ) && (attempt <= 2)){
			advancedZwaveSend( zwave.configurationV1.configurationGet(parameterNumber: (int) inputs.parameterNumber))
			hubitat.zwave.Command report = myReportQueue("7006").poll((int) inputs.waitTime, TimeUnit.SECONDS)
			if (logEnable) log.debug "Device ${device.displayName}: Received a parameter configuration report: ${report}."		
			rValue = getParameterStorageMap()?.get((int) inputs.parameterNumber)
			attempt++
	}
	return rValue
}

hubitat.zwave.Command setParameter(parameterNumber, value) {setParameter(parameterNumber:parameterNumber, value:value)}
hubitat.zwave.Command setParameter( Map params = [:]){
	Map inputs = [parameterNumber: null , value: null , waitTime: 5] << params
	if (inputs.parameterNumber.is( null ) || inputs.value.is( null ) )   {
			log.error "In function setParameter, parameter number or value was null. ${inputs}"
			// return false
		}
	if (!parameterRangeValid(inputs)) {
		log.error "For parameter ${inputs.parameterNumber}, value specified ${inputs.value} is not within permitted range."
		// return false
	}

	advancedZwaveSend(zwave.configurationV1.configurationSet(scaledConfigurationValue: (inputs.value as BigInteger), parameterNumber: (inputs.parameterNumber), size: (getParameterSize(inputs.parameterNumber)) ))

	advancedZwaveSend( zwave.configurationV1.configurationGet(parameterNumber: (inputs.parameterNumber)))
	
	hubitat.zwave.Command  report = myReportQueue("7006").poll(inputs.waitTime, TimeUnit.SECONDS) 
	if (logEnable || (!report)) log.debug "Device ${device.displayName}: attempted setting parameter ${inputs.parameterNumber} to value ${inputs.value}. Received report ${report}"
	
	return report
}



void clearSettings(){
	List<String> preserve = ["txtEnable", "logEnable"] // If particular settings are to be preserved, list them here

    def keys = new HashSet(settings.keySet())
    keys.each{ key -> device.removeSetting(key) }
	 
	device.removeSetting("")
	log.info "Device ${device.displayName}: Remaining preference settings after cleanup are: " + settings
}


// This is called within the updated() routine to update values!
Boolean updateDeviceSettings(){
	Map rValue = getAllParameterCharacteristics()?.collectEntries{ k, v ->
		Integer thisSettingValue = (settings?.get("${k}" as String)) as Integer
		Integer currentParameterValue = getParameterValue(parameterNumber:(k as Integer))

		Integer newValue = null
		
		if (thisSettingValue.is( null ) ) {
			newValue = getParameterValue(parameterNumber: (k as Integer))
			if (!(newValue.is( null ))) return [(k):(newValue)]
		} else if (thisSettingValue != currentParameterValue) {
			hubitat.zwave.Command report = setParameter(parameterNumber:(k as Integer), value: (thisSettingValue as Integer) )
			if (report?.parameterNumber) { 
				return [(report.parameterNumber as Integer):(report?.scaledConfigurationValue as Integer)]
			}
		} else {
			return [(k):(currentParameterValue)]
		}
	}
	if (rValue != parameterStorageMap) log.warn "Mismatch between maps. rValue = ${rValue}, parameterStorageMap = ${parameterStorageMap}"
	parameterStorageMap?.each{ k, v -> device.updateSetting("${k}" as String, v as Integer)}
	log.info "New Settings are: " + settings
	return true
}


//////////////////////////////////////////////////////////////////////
//////                  Report Queues                          ///////
//////////////////////////////////////////////////////////////////////
// reportQueues stores a map of SynchronousQueues. When requesting a report from a device, the report handler communicates the report back to the requesting function using a queue. This makes programming more like "synchronous" programming, rather than asynchronous handling.
// This is a map within a map. The first level map is by deviceNetworkId. Since @Field static variables are shared among all devices using the same driver code, this ensures that you get a unique second-level map for a particular device. The second map is keyed by the report class hex string. For example, if you want to wait for the configurationGet report, wait for "7006".
@Field static reportQueues = new ConcurrentHashMap<String, ConcurrentHashMap>(128, 0.75, 1)

SynchronousQueue myReportQueue(String reportClass) {
	ConcurrentHashMap thisDeviceQueues = reportQueues.get(device.deviceNetworkId, new ConcurrentHashMap<String,SynchronousQueue>(32, 0.75, 1))
	
	// Get the queue if it exists, create (new) it if it does not.
	SynchronousQueue thisReportQueue = thisDeviceQueues.get(reportClass, new SynchronousQueue())
	return thisReportQueue
}