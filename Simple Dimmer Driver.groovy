import java.util.concurrent.* // Available (allow-listed) concurrency classes: ConcurrentHashMap, ConcurrentLinkedQueue, Semaphore, SynchronousQueue
import groovy.transform.Field

//////////////
#include zwaveTools.sendReceiveTools
void supervisionCheckResendInfo(report){
	log.warn "Device ${device.displayName}: Supervision Check is resending command: ${report.command} for endpoint ${report.endPoint}, previous attempts: ${report.attempt}."
}
Integer getS2MaxRetries() { return 5 }
Integer getS2RetryPeriod() { return 1500}
/////////// 
#include zwaveTools.globalDataTools
#include zwaveTools.endpointTools
#include zwaveTools.batteryTools
#include zwaveTools.zwaveDeviceDatabase
#include zwaveTools.notificationTools
#include zwaveTools.meterTools
#include zwaveTools.sensorTools
#include zwaveTools.binaryAndMultilevelDeviceTools
#include zwaveTools.centralSceneTools
/////////////////


metadata {
	definition (name: "Any Z-Wave Dimmer Driver v1.5.5",namespace: "jvm", author: "jvm") {
		capability "Initialize"
		capability "Refresh"

		capability "Actuator"
		capability "Switch"
		capability "SwitchLevel"
		
       // capability "Sensor"				
        // capability "MotionSensor"
        // capability "TamperAlert"
		// capability "WaterSensor"
		// capability "ContactSensor"
		// capability "ShockSensor"		// Use this for glass breakage!
		// capability "IllumanceMeasurement"
		// capability "LiquidFlowRate"
		// attribute "carbonDioxideDetected"
		
		capability "EnergyMeter"
        capability "PowerMeter"
		capability "VoltageMeasurement"
        capability "CurrentMeter"
		attribute "energyConsumed", "number" 	// Custom Attribute for meter devices supporting energy consumption. Comment out if not wanted.
		attribute "powerFactor", "number"	// Custom Attribute for meter devices supporting powerFactor. Comment out if not wanted.
		attribute "pulseCount", "number"		// Custom Attribute for meter devices supporting powerFactor. Comment out if not wanted.
		attribute "reactiveCurrent", "number"		// Custom Attribute for meter devices supporting powerFactor. Comment out if not wanted.
		attribute "reactivePower", "number"		// Custom Attribute for meter devices supporting powerFactor. Comment out if not wanted.
		
		// capability "Battery"

		// capability "Consumable" 		// For smoke, CO, CO2 alarms that report their end-of-life
		// capability "FilterStatus" 	// For water filters that report status of filter
		
		capability "PushableButton"
		capability "HoldableButton"
		capability "ReleasableButton"
		capability "DoubleTapableButton"	
		attribute "multiTapButton", "number"

		command "identify" // implements the Z-Wave Plus identify function which can flash device indicators.
		command "resetDriver" // deletes the stored state information
							
        command "multiTap", [[name:"button",type:"NUMBER", description:"Button Number", constraints:["NUMBER"]],
		 			[name:"taps",type:"NUMBER", description:"Tap count", constraints:["NUMBER"]]]	

		command "setParameter",[[name:"parameterNumber",type:"NUMBER", description:"Parameter Number", constraints:["NUMBER"]],
					[name:"value",type:"NUMBER", description:"Parameter Value", constraints:["NUMBER"]]
					]	

		// Following Command is to help create a new data record to be added to deviceDatabase
        command "logDataRecord"

    }
	
	preferences 
	{	
        input name: "showParameterInputs", type: "bool", title: "Show Parameter Value Input Controls", defaultValue: false    
		input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
		input name: "txtEnable", type: "bool", title: "Enable text logging", defaultValue: true
		if (showParameterInputs) {
			getParameterValuesFromDevice()
			deviceInputs?.each{key, value -> input value}
        }
    }	
}
/////////////////////////////////////////////////////////////////////////
//////        Create and Manage Child Devices for Endpoints       ///////
/////////////////////////////////////////////////////////////////////////

void logDataRecord() {
log.debug dataRecordByProductType
}

void deleteUnwantedChildDevices()
{	
	// Delete child devices that don't use the proper network ID form (parent ID, followed by "-ep" followed by endpoint number).
	getChildDevices()?.each
	{ child ->	
	
		List childNetIdComponents = child.deviceNetworkId.split("-ep")
		if ((thisDeviceDataRecord.endpoints.containsKey(childNetIdComponents[1] as Integer)) && (childNetIdComponents[0] == device.deviceNetworkId)) {
			return
		} else {
			deleteChildDevice(child.deviceNetworkId)
		}			
	}
}

void createChildDevices()
{	
	thisDeviceDataRecord.endpoints.findAll{k, v -> (k != 0)}.each
	{ ep, value ->
		String childNetworkId = "${device.deviceNetworkId}-ep${"${ep}".padLeft(3, "0") }"
		com.hubitat.app.DeviceWrapper cd = getChildDevice(childNetworkId)
		if (cd.is( null )) {
			log.info "Device ${device.displayName}: creating child device: ${childNetworkId} with driver ${value.driver.type} and namespace: ${value.driver.namespace}."
			
			addChildDevice(value.driver.namespace, value.driver.type, childNetworkId, [name: value.driver.childName ?:"${device.displayName}-ep${ep}", isComponent: false])
		} 
	}
}
/////////////////////////////////////////////////////////////////

void identify() {
	log.warn "Device ${device.displayName}: The 'identify' function is experimental and only works for Zwave Plus Version 2 or greater devices!"
	// Identify function supported by Zwave Plus Version 2 and greater devices!
		List<Map<String, Short>> indicators = [
			[indicatorId:0x50, propertyId:0x03, value:0x08], 
			[indicatorId:0x50, propertyId:0x04, value:0x03],  
			[indicatorId:0x50, propertyId:0x05, value:0x06]
		]
		advancedZwaveSend(zwave.indicatorV3.indicatorSet(indicatorCount:3 , value:0, indicatorValues: indicators ))
}


Map reparseDeviceData(deviceData = null )
{
	// When data is stored in the state.deviceRecord it can lose its original data types, so need to restore after reading the data froms state.
	// This is only done during the startup / initialize routine and results are stored in a global variable, so it is only done for the first device of a particular model.

	if (deviceData.is( null )) return null
	Map reparsed = [formatVersion: null , fingerprints: null , classVersions: null ,endpoints: null , deviceInputs: null ]

	reparsed.formatVersion = deviceData.formatVersion as Integer
	
	if (deviceData.endpoints) {
		reparsed.endpoints = deviceData.endpoints.collectEntries{k, v -> [(k as Integer), (v)] }
	} else {
		List<Integer> endpoint0Classes = getDataValue("inClusters")?.split(",").collect{ hexStrToUnsignedInt(it) as Integer }
						endpoint0Classes += getDataValue("secureInClusters")?.split(",").collect{ hexStrToUnsignedInt(it) as Integer }
		if (endpoint0Classes.contains(0x60))
			{
				log.error "Device ${device.displayName}: Error in function reparseDeviceData. Missing endpoint data for a multi-endpoint device. This usually occurs if there is a locally stored data record which does not properly specify the endpoint data. This device may still function, but only for the root device."
			}
		reparsed.endpoints = [0:[classes:(endpoint0Classes)]]
	}
	
	reparsed.deviceInputs = deviceData.deviceInputs?.collectEntries{ k, v -> [(k as Integer), (v)] }
	reparsed.fingerprints = deviceData.fingerprints?.collect{ it -> [manufacturer:(it.manufacturer as Integer), deviceId:(it.deviceId as Integer),  deviceType:(it.deviceType as Integer), name:(it.name)] }
	if (deviceData.classVersions) reparsed.classVersions = deviceData.classVersions?.collectEntries{ k, v -> [(k as Integer), (v as Integer)] }
	if (logEnable) "Device ${device.displayName}: Reparsed data is ${reparsed}"
	return reparsed
}

void resetDriver() {
	state.clear()
}

void clearLeftoverStates() {
	List<String> allowed = ["deviceRecord"] 
	
	// Can't modify state from within state.each{}, so first collect what is unwanted, then remove in a separate unwanted.each
	List<String> unwanted = state.collect{ 
			if (allowed.contains( it.key as String)) return
			return it.key
		}.each{state.remove( it ) }
}


void removeAllSettings() {
    if (logEnable) log.debug "settings before clearing: " + settings
    // Copy keys set first to avoid any chance of concurrent modification
    def keys = new HashSet(settings.keySet())
    keys.each{ key -> device.removeSetting(key) }
     if (logEnable) log.debug "settings after clearing: " + settings
}

void initialize()
{
	// removeAllSettings()
	// By default, hide the parameter settings inputs since displaying them forces a refresh of all values the first time they are shown and is time consuming!
    device.updateSetting("showParameterInputs",[value:"false",type:"bool"])

	clearLeftoverStates()
	log.info "Device ${device.displayName}: Initializing."

	/////////////////////////////////////////////////////////////////////////////////////
	///                      Don't Alter this code block code!                        ///
	/// This code manages the different ways in which the device record may be stored ///
	///             - i.e., locally or from the openSmartHouse database               ///
	/////////////////////////////////////////////////////////////////////////////////////
	// If the format of the device record has changed, delete any locally stored data and recreate 
	if ((state.deviceRecord?.formatVersion as Integer) != dataRecordFormatVersion) state.remove("deviceRecord")
	
	Map localDataRecord = getThisDeviceDatabaseRecord()
	if (localDataRecord && (localDataRecord.formatVersion != dataRecordFormatVersion)) {
		log.warn "Device ${device.displayName}: Locally stored data record has wrong version number and will be ignored. Obtaining data from openSmartHouse instead. Locally stored record is: ${localDataRecord.inspect()}."
		}
		
	if (localDataRecord && (localDataRecord.formatVersion == dataRecordFormatVersion)){
		state.remove("deviceRecord") // If a device data record was added to the database, delete if it was previously from openSmartHouse.
		dataRecordByProductType.put("deviceRecord", reparseDeviceData(localDataRecord)) // Store in the Global ConcurrentHashMap
	} else if ( state.deviceRecord && getDataRecordByProductType().deviceRecord.is( null ) ) { 
		// Put in the Global ConcurrentHashMap if it exist locally.
		dataRecordByProductType.put("deviceRecord", reparseDeviceData(localDataRecord)) // Store in the Global ConcurrentHashMap
	} else if ( state.deviceRecord.is( null ) && getDataRecordByProductType().deviceRecord ) {
		// Data record doesn't exist in state, but it is in the concurrentHashMap - So store in state rather than re-retrieve
		state.deviceRecord = dataRecordByProductType.deviceRecord
	} else if ( state.deviceRecord.is( null )) {
		// Data record doesn't exist - get it and store in the global data record
		Map createdRecord = createDeviceDataRecord() 
		state.deviceRecord = createdRecord
		if (createdRecord) dataRecordByProductType.put("deviceRecord", reparseDeviceData(localDataRecord))
	}
	///////////////////////////////////////////////////////////////////////////////////
	//////////          Done with Device Data Record Management      //////////////////
	///////////////////////////////////////////////////////////////////////////////////	
	List<Integer> supportedClasses = getThisEndpointClasses(ep)
	
	// Create child devices if this is a multi-channel device.
	if (getDataRecordByProductType().classVersions?.containsKey(0x60)) {
		deleteUnwantedChildDevices()
		createChildDevices()
		}

	if (getDataRecordByProductType().classVersions?.containsKey(0x5B)) advancedZwaveSend(zwave.centralSceneV3.centralSceneSupportedGet())
	if (getDataRecordByProductType().classVersions?.containsKey(0x6C)) sendInitialCommand()
	
	if (txtEnable) log.info "Device ${device.displayName}: Refreshing device data."
	refresh()  
	
	if (txtEnable) log.info "Device ${device.displayName}: Done Initializing."

}


//////////// Get Inputs //////////////
Map getThisDeviceDataRecord(){
	getDataRecordByProductType()?.deviceRecord
}

Map getDeviceInputs()  { 
	Map returnMe = getThisDeviceDataRecord()?.deviceInputs.sort({it.key})
	if (logEnable && returnMe.is( null ) ) log.warn "Device ${device.displayName}: Device has no inputs. Check if device was initialized. returnMe is ${returnMe}."
	return returnMe
}

Map filteredDeviceInputs() {
	if (advancedEnable) { 
		return getDeviceInputs()?.sort()
	} else  { // Just show the basic items
		return 	getDeviceInputs()?.findAll { it.value.category != "advanced" }?.sort()
	}
}

///////////////////////////////////////////////////////////////////////////////////////
///////      Handle Refreshes      ////////
/////////////////////////////////////////////////////////////////////////////////////// 
void componentRefresh(com.hubitat.app.DeviceWrapper cd){
	refreshEndpoint(cd:cd)
}

void refreshEndpoint(Map params = [cd: null, ep: null ])
{
	// com.hubitat.app.DeviceWrapper targetDevice = device
	Integer ep = null
	if (params.cd) {
			ep = (params.cd.deviceNetworkId.split("-ep")[-1]) as Integer
	} else if (! params.ep.is( null )) {
		ep = params.ep as Integer
	}
	if (ep.is( null )) return
	
	Map record = getThisEndpointData(ep)
		if (logEnable) log.debug "Device ${device.displayName}: Refreshing endpoint: ${ep ?: 0} with record ${record}"
		if (txtEnable) log.info "Device ${device.displayName}: refreshing values for endpoint ${ep}."
		List<Integer> supportedClasses = getThisEndpointClasses(ep)
		if (supportedClasses.contains(0x25)) 		advancedZwaveSend(zwave.switchBinaryV1.switchBinaryGet(), ep)
		if (supportedClasses.contains(0x26)) 		advancedZwaveSend(zwave.switchMultilevelV4.switchMultilevelGet(), ep)
		if (supportedClasses.contains(0x32) && meterTools_refresh) 			meterTools_refresh(ep)
		if (supportedClasses.contains(0x71) && notificationTools_refresh ) 	notificationTools_refresh(ep)
		// if (record.classes.contains(0x62)) 		refreshLock(ep)
		if (supportedClasses.contains(0x80)) 		batteryTools_refreshBattery()
}

void refresh()
{
	getFullEndpointRecord().each{thisEp, v ->
		refreshEndpoint(ep:thisEp)
	}
}

void	refreshLock(ep = null ) {
	log.error "Device ${device.displayName} Function refreshLock is not fully implemented."
}

/////////////////////////////////////////////////////////////////////////////////////// 
///////                   Parameter Updating and Management                    ////////
///////      Handle Update(), and Set, Get, and Process Parameter Values       ////////
/////////////////////////////////////////////////////////////////////////////////////// 

void logsOff() {
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

void updated()
{
	if (txtEnable) log.info "Device ${device.displayName}: Updating changed parameters (if any) . . ."
	if (logEnable) runIn(1800,logsOff)
	
	ConcurrentHashMap<Integer, BigInteger> parameterValueMap = getParameterValuesFromDevice()
	if (parameterValueMap.is( null ))
		{
			log.error "In function updated, parameterValueMap is ${parameterValueMap}"
			return
		}

	ConcurrentHashMap<Integer, BigInteger> pendingChanges = getPendingChangeMap()

	Map<Integer, BigInteger>  settingValueMap = getParameterValuesFromInputControls()
	// if (logEnable ) log.debug "Device ${device.displayName}: Current input control values are: ${settingValueMap}"

	// Find what changed
	settingValueMap.findAll{k, v -> !(v.is( null ))}.each {k, v ->
			Boolean changedValue = ((v as BigInteger) != (parameterValueMap.get(k as Integer) as BigInteger)) 
			if (changedValue) {
				pendingChanges?.put(k as Integer, v as BigInteger)
			} else pendingChanges?.remove(k)
		}

	if (txtEnable) log.info "Device ${device.displayName}: Pending parameter changes are: ${pendingChanges ?: "None"}"
	
	processPendingChanges()
	if (txtEnable) log.info "Device ${device.displayName}: Done updating changed parameters (if any) . . ."

}

void processPendingChanges()
{
	if (txtEnable) log.info "Device ${device.displayName}: Processing Pending parameter changes: ${getPendingChangeMap()}"
		pendingChangeMap?.findAll{k, v -> !(v.is( null ))}.each{ k, v ->
			if (txtEnable) log.info "Updating parameter ${k} to value ${v}"
			setParameter(parameterNumber: k , value: v)
		}
}

void setParameter(parameterNumber, value = null ) {
	if (parameterNumber && ( ! value.is( null) )) {
		setParameter(parameterNumber:parameterNumber, value:value)
	} else if (parameterNumber) {
		advancedZwaveSend( zwave.configurationV1.configurationGet(parameterNumber: parameterNumber))
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

	advancedZwaveSend(zwave.configurationV1.configurationSet(scaledConfigurationValue: params.value as BigInteger, parameterNumber: params.parameterNumber, size: PSize))
	// The 'get' should not be supervised!
	advancedZwaveSend( zwave.configurationV1.configurationGet(parameterNumber: params.parameterNumber))
	
	// Wait for the report that is returned after the configurationGet, and then update the input controls so they display the updated value.
	Boolean success = myReportQueue("7006").poll(5, TimeUnit.SECONDS) ? true : false 

}

// Gets a map of all the values currently stored in the input controls.
Map<Integer, BigInteger> getParameterValuesFromInputControls()
{
	ConcurrentHashMap inputs = getDeviceInputs()
	
	if (!inputs) return
	
	Map<Integer, BigInteger> settingValues = [:]
	
	inputs.each 
		{ PKey , PData -> 
			BigInteger newValue = 0
			// if the setting returns an array, then it is a bitmap control, and add together the values.
			
			if (settings.get(PData.name as String) instanceof ArrayList) {
				settings.get(PData.name as String).each{ newValue += it as BigInteger }
			} else  {   
				newValue = settings.get(PData.name as String) as BigInteger  
			}
			settingValues.put(PKey, newValue)
		}
	if (txtEnable) log.info "Device ${device.displayName}: Current Parameter Setting Values are: " + settingValues
	return settingValues
}

@Field static  ConcurrentHashMap<String, ConcurrentHashMap> allPendingParameterChanges = new ConcurrentHashMap<String, ConcurrentHashMap>(128, 0.75, 1)
@Field static  ConcurrentHashMap<String, ConcurrentHashMap> allDevicesParameterValues = new ConcurrentHashMap<String, ConcurrentHashMap>(128, 0.75, 1)

ConcurrentHashMap getPendingChangeMap() {
	return  allPendingParameterChanges.get(device.deviceNetworkId, new ConcurrentHashMap(32, 0.75, 1) )
}

Map<Integer, BigInteger> getParameterValuesFromDevice()
{
	ConcurrentHashMap parameterValues = allDevicesParameterValues.get(device.deviceNetworkId, new ConcurrentHashMap<Integer, BigInteger>(32, 0.75, 1))
	
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
					advancedZwaveSend(zwave.configurationV2.configurationGet(parameterNumber: k))
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
	ConcurrentHashMap parameterValues = allDevicesParameterValues.get(device.deviceNetworkId, new ConcurrentHashMap<Integer, BigInteger>(32, 0.75, 1))
	BigInteger newValue = (cmd.size == 1) ? cmd.configurationValue[0] : cmd.scaledConfigurationValue			
	if (newValue < 0) log.warn "Device ${device.displayName}: Negative configuration value reported for configuration parameter ${cmd.parameterNumber}."
				
	parameterValues.put((cmd.parameterNumber as Integer), newValue )
	
	pendingChangeMap.remove(cmd.parameterNumber as Integer)
	
	if (txtEnable) log.info "Device ${device.displayName}: updating parameter: ${cmd.parameterNumber} to ${newValue}."
	device.updateSetting("${cmd.parameterNumber}", newValue as Integer)
		
	myReportQueue(cmd.CMD).offer( cmd )
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

//////////////////////////////////////////////////////////////////////
//////                  Z-Wave Helper Functions                ///////
//////   Format messages, Send to Device, secure Messages      ///////
//////////////////////////////////////////////////////////////////////

////    Hail   ////
void zwaveEvent(hubitat.zwave.commands.hailv1.Hail cmd) {
	refresh()
}

//////////////////////////////////////////////////////////////////////
//////      Get Device's Database Information           ///////
////////////////////////////////////////////////////////////////////// 
Map getSpecificRecord(id)
{
    String queryByDatabaseID= "http://www.opensmarthouse.org/dmxConnect/api/zwavedatabase/device/read.php?device_id=${id}"    
    
	httpGet([uri:queryByDatabaseID]) { resp-> 
				return resp?.data
			}
}

Map createDeviceDataRecord()
{
	Map firstQueryRecord = getOpenSmartHouseData();
	
	if (firstQueryRecord.is( null )) {
	log.error "Device ${device.displayName}: Failed to retrieve data record identifier for device from OpenSmartHouse Z-Wave database. OpenSmartHouse database may be unavailable. Try again later or check database to see if your device can be found in the database."
	}

	Map thisRecord = getSpecificRecord(firstQueryRecord.id)
	
	if (thisRecord.is( null )) {
	log.error "Device ${device.displayName}: Failed to retrieve data record for device from OpenSmartHouse Z-Wave database. OpenSmartHouse database may be unavailable. Try again later or check database to see if your device can be found in the database."
	}

	Map deviceRecord = [fingerprints: [] , endpoints: [:] , deviceInputs: null ]
	Map thisFingerprint = [manufacturer: (getDataValue("manufacturer")?.toInteger()) , deviceId: (getDataValue("deviceId")?.toInteger()) ,  deviceType: (getDataValue("deviceType")?.toInteger()) ]
	thisFingerprint.name = "${firstQueryRecord.manufacturer_name}: ${firstQueryRecord.label}" as String
	
	deviceRecord.fingerprints.add(thisFingerprint )
	
	deviceRecord.deviceInputs = createInputControls(thisRecord.parameters)
	deviceRecord.classVersions = getRootClassData(thisRecord.endpoints)
	deviceRecord.endpoints = getEndpointClassData(thisRecord.endpoints)
	deviceRecord.formatVersion = dataRecordFormatVersion
	
	return deviceRecord
}
	
// List getOpenSmartHouseData()
Map getOpenSmartHouseData()
{
	if (txtEnable) log.info "Getting data from OpenSmartHouse for device ${device.displayName}."
	String manufacturer = 	hubitat.helper.HexUtils.integerToHexString( device.getDataValue("manufacturer").toInteger(), 2)
	String deviceType = 	hubitat.helper.HexUtils.integerToHexString( device.getDataValue("deviceType").toInteger(), 2)
	String deviceID = 		hubitat.helper.HexUtils.integerToHexString( device.getDataValue("deviceId").toInteger(), 2)

    String DeviceInfoURI = "http://www.opensmarthouse.org/dmxConnect/api/zwavedatabase/device/list.php?filter=manufacturer:0x${manufacturer}%20${deviceType}:${deviceID}"

    Map thisDeviceData
			
    httpGet([uri:DeviceInfoURI])
    { 
		resp ->
		Map maxRecord = resp.data.devices.max(			{ a, b -> 
				List<Integer> a_version = a.version_max.split("\\.")
				List<Integer> b_version = b.version_max.split("\\.")
			
				Float a_value = a_version[0].toFloat() + (a_version[1].toFloat() / 1000)
				Float b_value = b_version[0].toFloat() + (b_version[1].toFloat() / 1000)
				
				(a_value <=> b_value)
			})
		return maxRecord
	}
}

Map getRootClassData(endpointRecord) {
	endpointRecord.find{ it.number == 0}.commandclass.collectEntries{thisClass ->
					[(classMappings.get(thisClass.commandclass_name, 0) as Integer), (thisClass.version as Integer)]
				}
}

String getChildComponentDriver(List classes)
{
	if (classes.contains(0x25) ){  // Binary Switch
		if (classes.contains(0x32) ){ // Meter Supported
			return "Generic Component Metering Switch"
		} else {
			return "Generic Component Switch"
		}
	} else  if (classes.contains(0x26)){ // MultiLevel Switch
		if (classes.contains(0x32) ){ // Meter Supported
			return "Generic Component Metering Dimmer"
		} else {
			return "Generic Component Dimmer"
		}			
	}
	return "Generic Component Dimmer"
}

Map getEndpointClassData(endpointRecord)
{
	Map endpointClassMap = [:]

	endpointRecord.each{ it ->
			List thisEndpointClasses =  it.commandclass.collect{thisClass -> classMappings.get(thisClass.commandclass_name, 0) as Integer }
			
			if (it.number == 0) {
				endpointClassMap.put((it.number as Integer), [classes:(thisEndpointClasses)])
				return
			} else {
				String childDriver = getChildComponentDriver(thisEndpointClasses)
				endpointClassMap.put((it.number as Integer), [driver:[type:childDriver, namespace:"hubitat"], classes:(thisEndpointClasses)])
			}
		}
    return endpointClassMap
}

Map createInputControls(data)
{
	if (!data) return null
	
	Map inputControls = [:]	
	data?.each
	{
		if (it.read_only as Integer) {
				log.info "Device ${device.displayName}: Parameter ${it.param_id}-${it.label} is read-only. No input control created."
				return
			}
	
		if (it.bitmask.toInteger())
		{
			if (!(inputControls?.get(it.param_id)))
			{
				log.warn "Device ${device.displayName}: Parameter ${it.param_id} is a bitmap field. This is poorly supported. Treating as an integer - rely on your user manual for proper values!"
				String param_name_string = "${it.param_id}"
				String title_string = "(${it.param_id}) ${it.label} - bitmap"
				Map newInput = [name:param_name_string , type:"number", title: title_string, size:it.size]
				if ((it.description.size() != 0) && (it.description != it.label)) newInput.description = it.description
				
				inputControls.put(it.param_id, newInput)
			}
		} else {
			String param_name_string = "${it.param_id}"
			String title_string = "(${it.param_id}) ${it.label}"
			
			Map newInput = [name: param_name_string, title: title_string, size:it.size]
			if ((it.description.size() != 0) && (it.description != it.label)) newInput.description = it.description

			def deviceOptions = [:]
			it.options.each { deviceOptions.put(it.value, it.label) }
			
			// Set input type. Should be one of: bool, date, decimal, email, enum, number, password, time, text. See: https://docs.hubitat.com/index.php?title=Device_Preferences
			
			// use enum but only if it covers all of the choices!
			Integer numberOfValues = (it.maximum - it.minimum) +1
			if (deviceOptions && (deviceOptions.size() == numberOfValues) )
			{
				newInput.type = "enum"
				newInput.options = deviceOptions
			} else {
				newInput.type = "number"
				newInput.range = "${it.minimum}..${it.maximum}"
			}
			inputControls[it.param_id] = newInput
		}
	}
	return inputControls
}

@Field static Map classMappings = [
	COMMAND_CLASS_ALARM:0x71,
	COMMAND_CLASS_SENSOR_ALARM :0x9C,
	COMMAND_CLASS_SILENCE_ALARM:0x9D,
	COMMAND_CLASS_SWITCH_ALL:0x27,
	COMMAND_CLASS_ANTITHEFT:0x5D,
	COMMAND_CLASS_ANTITHEFT_UNLOCK:0x7E,
	COMMAND_CLASS_APPLICATION_CAPABILITY:0x57,
	COMMAND_CLASS_APPLICATION_STATUS:0x22,
	COMMAND_CLASS_ASSOCIATION:0x85,
	COMMAND_CLASS_ASSOCIATION_COMMAND_CONFIGURATION:0x9B,
	COMMAND_CLASS_ASSOCIATION_GRP_INFO:0x59,
	COMMAND_CLASS_AUTHENTICATION:0xA1,
	COMMAND_CLASS_AUTHENTICATION_MEDIA_WRITE:0xA2,
	COMMAND_CLASS_BARRIER_OPERATOR:0x66,
	COMMAND_CLASS_BASIC:0x20,
	COMMAND_CLASS_BASIC_TARIFF_INFO:0x36,
	COMMAND_CLASS_BASIC_WINDOW_COVERING:0x50,
	COMMAND_CLASS_BATTERY:0x80,
	COMMAND_CLASS_SENSOR_BINARY:0x30,
	COMMAND_CLASS_SWITCH_BINARY:0x25,
	COMMAND_CLASS_SWITCH_TOGGLE_BINARY:0x28,
	COMMAND_CLASS_CLIMATE_CONTROL_SCHEDULE:0x46,
	COMMAND_CLASS_CENTRAL_SCENE:0x5B,
	COMMAND_CLASS_CLOCK:0x81,
	COMMAND_CLASS_SWITCH_COLOR:0x33,
	COMMAND_CLASS_CONFIGURATION:0x70,
	COMMAND_CLASS_CONTROLLER_REPLICATION:0x21,
	COMMAND_CLASS_CRC_16_ENCAP:0x56,
	COMMAND_CLASS_DCP_CONFIG:0x3A,
	COMMAND_CLASS_DCP_MONITOR:0x3B,
	COMMAND_CLASS_DEVICE_RESET_LOCALLY:0x5A,
	COMMAND_CLASS_DOOR_LOCK:0x62,
	COMMAND_CLASS_DOOR_LOCK_LOGGING:0x4C,
	COMMAND_CLASS_ENERGY_PRODUCTION:0x90,
	COMMAND_CLASS_ENTRY_CONTROL :0x6F,
	COMMAND_CLASS_FIRMWARE_UPDATE_MD:0x7A,
	COMMAND_CLASS_GENERIC_SCHEDULE:0xA3,
	COMMAND_CLASS_GEOGRAPHIC_LOCATION:0x8C,
	COMMAND_CLASS_GROUPING_NAME:0x7B,
	COMMAND_CLASS_HAIL:0x82,
	COMMAND_CLASS_HRV_STATUS:0x37,
	COMMAND_CLASS_HRV_CONTROL:0x39,
	COMMAND_CLASS_HUMIDITY_CONTROL_MODE:0x6D,
	COMMAND_CLASS_HUMIDITY_CONTROL_OPERATING_STATE:0x6E,
	COMMAND_CLASS_HUMIDITY_CONTROL_SETPOINT:0x64,
	COMMAND_CLASS_INCLUSION_CONTROLLER:0x74,
	COMMAND_CLASS_INDICATOR:0x87,
	COMMAND_CLASS_IP_ASSOCIATION:0x5C,
	COMMAND_CLASS_IP_CONFIGURATION:0x9A,
	COMMAND_CLASS_IR_REPEATER:0xA0,
	COMMAND_CLASS_IRRIGATION:0x6B,
	COMMAND_CLASS_LANGUAGE:0x89,
	COMMAND_CLASS_LOCK:0x76,
	COMMAND_CLASS_MAILBOX:0x69,
	COMMAND_CLASS_MANUFACTURER_PROPRIETARY:0x91,
	COMMAND_CLASS_MANUFACTURER_SPECIFIC:0x72,
	COMMAND_CLASS_MARK:0xEF,
	COMMAND_CLASS_METER:0x32,
	COMMAND_CLASS_METER_TBL_CONFIG:0x3C,
	COMMAND_CLASS_METER_TBL_MONITOR:0x3D,
	COMMAND_CLASS_METER_TBL_PUSH:0x3E,
	COMMAND_CLASS_MTP_WINDOW_COVERING:0x51,
	COMMAND_CLASS_MULTI_CHANNEL:0x60,
	COMMAND_CLASS_MULTI_CHANNEL_ASSOCIATION:0x8E,
	COMMAND_CLASS_MULTI_CMD:0x8F,
	COMMAND_CLASS_SENSOR_MULTILEVEL:0x31,
	COMMAND_CLASS_SWITCH_MULTILEVEL:0x26,
	COMMAND_CLASS_SWITCH_TOGGLE_MULTILEVEL:0x29,
	COMMAND_CLASS_NETWORK_MANAGEMENT_BASIC:0x4D,
	COMMAND_CLASS_NETWORK_MANAGEMENT_INCLUSION:0x34,
	NETWORK_MANAGEMENT_INSTALLATION_MAINTENANCE:0x67,
	COMMAND_CLASS_NETWORK_MANAGEMENT_PRIMARY:0x54,
	COMMAND_CLASS_NETWORK_MANAGEMENT_PROXY:0x52,
	COMMAND_CLASS_NO_OPERATION:0x00,
	COMMAND_CLASS_NODE_NAMING:0x77,
	COMMAND_CLASS_NODE_PROVISIONING:0x78,
	COMMAND_CLASS_NOTIFICATION:0x71,
	COMMAND_CLASS_POWERLEVEL:0x73,
	COMMAND_CLASS_PREPAYMENT:0x3F,
	COMMAND_CLASS_PREPAYMENT_ENCAPSULATION:0x41,
	COMMAND_CLASS_PROPRIETARY:0x88,
	COMMAND_CLASS_PROTECTION:0x75,
	COMMAND_CLASS_METER_PULSE:0x35,
	COMMAND_CLASS_RATE_TBL_CONFIG:0x48,
	COMMAND_CLASS_RATE_TBL_MONITOR:0x49,
	COMMAND_CLASS_REMOTE_ASSOCIATION_ACTIVATE:0x7C,
	COMMAND_CLASS_REMOTE_ASSOCIATION:0x7D,
	COMMAND_CLASS_SCENE_ACTIVATION:0x2B,
	COMMAND_CLASS_SCENE_ACTUATOR_CONF:0x2C,
	COMMAND_CLASS_SCENE_CONTROLLER_CONF:0x2D,
	COMMAND_CLASS_SCHEDULE:0x53,
	COMMAND_CLASS_SCHEDULE_ENTRY_LOCK:0x4E,
	COMMAND_CLASS_SCREEN_ATTRIBUTES:0x93,
	COMMAND_CLASS_SCREEN_MD:0x92,
	COMMAND_CLASS_SECURITY:0x98,
	COMMAND_CLASS_SECURITY_2:0x9F,
	COMMAND_CLASS_SECURITY_SCHEME0_MARK :0xF100,
	COMMAND_CLASS_SENSOR_CONFIGURATION:0x9E,
	COMMAND_CLASS_SIMPLE_AV_CONTROL:0x94,
	COMMAND_CLASS_SOUND_SWITCH:0x79,
	COMMAND_CLASS_SUPERVISION:0x6C,
	COMMAND_CLASS_TARIFF_CONFIG:0x4A,
	COMMAND_CLASS_TARIFF_TBL_MONITOR:0x4B,
	COMMAND_CLASS_THERMOSTAT_FAN_MODE:0x44,
	COMMAND_CLASS_THERMOSTAT_FAN_STATE:0x45,
	COMMAND_CLASS_THERMOSTAT_MODE:0x40,
	COMMAND_CLASS_THERMOSTAT_OPERATING_STATE:0x42,
	COMMAND_CLASS_THERMOSTAT_SETBACK:0x47,
	COMMAND_CLASS_THERMOSTAT_SETPOINT:0x43,
	COMMAND_CLASS_TIME:0x8A,
	COMMAND_CLASS_TIME_PARAMETERS:0x8B,
	COMMAND_CLASS_TRANSPORT_SERVICE:0x55,
	COMMAND_CLASS_USER_CODE:0x63,
	COMMAND_CLASS_VERSION:0x86,
	COMMAND_CLASS_WAKE_UP:0x84,
	COMMAND_CLASS_WINDOW_COVERING:0x6A,
	COMMAND_CLASS_ZIP:0x23,
	COMMAND_CLASS_ZIP_6LOWPAN:0x4F,
	COMMAND_CLASS_ZIP_GATEWAY:0x5F,
	COMMAND_CLASS_ZIP_NAMING:0x68,
	COMMAND_CLASS_ZIP_ND:0x58,
	COMMAND_CLASS_ZIP_PORTAL:0x61,
	COMMAND_CLASS_ZWAVEPLUS_INFO:0x5E,
]