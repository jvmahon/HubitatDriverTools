import java.util.concurrent.* // Available (allow-listed) concurrency classes: ConcurrentHashMap, ConcurrentLinkedQueue, Semaphore, SynchronousQueue
import groovy.transform.Field
//////////////
#include zwaveTools.sendReceiveTools
Integer getS2MaxRetries() { return 5 }
/////////// 
#include zwaveTools.globalDataTools
#include zwaveTools.endpointTools
#include zwaveTools.batteryTools
#include zwaveTools.versionInfoTools
#include zwaveTools.zwaveDeviceDatabase
#include zwaveTools.notificationTools
#include zwaveTools.meterTools
#include zwaveTools.sensorTools
#include zwaveTools.binaryAndMultilevelDeviceTools
#include zwaveTools.centralSceneTools
#include zwaveTools.openSmarthouseTools
#include zwaveTools.childDeviceTools
#include zwaveTools.parameterGetSendTools

metadata {
	definition (name: "Any Z-Wave Device Universal Parent Driver v1.7.0",namespace: "jvm", author: "jvm", singleThreaded:false) {
		capability "Initialize"
		capability "Refresh"
	
		// Uncomment capabilities that you want to expose in the parent.
		// Otherwise, all capabilities / attributes are by adding child devices.
			// capability "Actuator"
			// capability "Switch"
			// capability "SwitchLevel"		

		capability "PushableButton"
		capability "HoldableButton"
		capability "ReleasableButton"
		capability "DoubleTapableButton"	
		attribute "multiTapButton", "number"
        // capability "Sensor"				
        // capability "MotionSensor"
        // capability "TamperAlert"
		// capability "WaterSensor"
		// capability "ContactSensor"
		// capability "ShockSensor"		// Use this for glass breakage!
		// capability "IllumanceMeasurement"
		// capability "LiquidFlowRate"
		// attribute "carbonDioxideDetected"
		
		// capability "Battery"

		// capability "Consumable" 		// For smoke, CO, CO2 alarms that report their end-of-life
		// capability "FilterStatus" 	// For water filters that report status of filter
		

		command "identify" // implements the Z-Wave Plus identify function which can flash device indicators.
		command "resetDriver" // deletes the stored state information

		command "addNewChildDevice", [[name:"Device Name*", type:"STRING"], 
                                      [name:"componentDriverName*",type:"ENUM", constraints:(getDriverChoices()) ], 
                                      [name:"Endpoint",type:"NUMBER", description:"Endpoint Number, blank or 0 = root" ] ]

  
        command "multiTap", [[name:"button",type:"NUMBER", description:"Button Number", constraints:["NUMBER"]],
		 			[name:"taps",type:"NUMBER", description:"Tap count", constraints:["NUMBER"]]]								
		command "setParameter",[[name:"parameterNumber",type:"NUMBER", description:"Parameter Number", constraints:["NUMBER"]],
					[name:"value",type:"NUMBER", description:"Parameter Value", constraints:["NUMBER"]]
					]	
		
		command "getParameter",[[name:"parameterNumber",type:"NUMBER", description:"Parameter Number", constraints:["NUMBER"]]
					]	
		command "refreshManufacturerSpecificInfo"
		
		command "showParameterStorageMap"
		command "clearSettings"
		
		// Following Command is to help create a new data record to be added to deviceDatabase
       //  command "logDataRecord"
	   
		fingerprint  mfr:"0346", prod:"0301", deviceId:"0301", deviceJoinName:"Ring G2 Motion Sensor"
		fingerprint  mfr:"027A", prod:"0301", deviceId:"0012", deviceJoinName:"Zooz: ZSE18"
		fingerprint  mfr:"000C", prod:"4447", deviceId:"3034", deviceJoinName:"HomeSeer WD100+"
		fingerprint  mfr:"000C", prod:"4447", deviceId:"3033", deviceJoinName:"HomeSeer WS100 Switch"
		fingerprint  mfr:"000C", prod:"4447", deviceId:"3036", deviceJoinName:"HomeSeer Technologies: HS-WD200+"
		fingerprint  mfr:"000C", prod:"4447", deviceId:"3035", deviceJoinName:"HomeSeer Technologies: HS-WS200+"
		fingerprint  mfr:"0063", prod:"4952", deviceId:"3135", deviceJoinName:"Jasco Products: 46201"
		fingerprint  mfr:"031E", prod:"000E", deviceId:"0001", deviceJoinName:"Inovelli LZW36 Light/Fan Controller"
		fingerprint  mfr:"0063", prod:"4F44", deviceId:"3032", deviceJoinName:"GE/Jasco Heavy Duty Switch 14285"
		fingerprint  mfr:"027A", prod:"A000", deviceId:"A003", deviceJoinName:"Zooz: ZEN25"
		fingerprint  mfr:"027A", prod:"A000", deviceId:"A001", deviceJoinName:"Zooz: ZEN26"
		fingerprint  mfr:"027A", prod:"A000", deviceId:"A003", deviceJoinName:"Zooz: ZEN30"
		fingerprint  mfr:"003B", prod:"0001", deviceId:"0469", deviceJoinName:"Allegion: BE469ZP"


    }
	
	preferences  {	
        input name: "showParameterInputs", type: "bool", title: "Show Parameter Value Input Controls", defaultValue: false    
		input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
		input name: "txtEnable", type: "bool", title: "Enable text logging", defaultValue: true

		if (showParameterInputs) {
			// parameterStorageMap.each{k, v -> device.updateSetting("${k}", v as Integer)}
			getInputs().each{key, value -> input value}
        }
    }	
}

Map getUserDefinedParameterInputs(){
	Map returnMe = getDataRecordByProductType()?.deviceInputs?.sort({it.key})
	if (logEnable && returnMe.is( null ) ) log.warn "Device ${device.displayName}: Device has no inputs. Check if device was initialized. returnMe is ${returnMe}."
	return returnMe
}

def updated(){
	// waiting for the Boolean prevents updated from returning prematurely
	Boolean done = updateDeviceSettings()
}

void initialize(){
    device.updateSetting("showParameterInputs",[value:"false",type:"bool"])
	
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
		state.remove("deviceRecord") // If a device data record from openSmartHouse was added to the state, delete it as it is now in the local database.
		dataRecordByProductType.putAll(reparseDeviceData(localDataRecord)) // Store in the Global ConcurrentHashMap
	} else if ( state.deviceRecord && getDataRecordByProductType().deviceRecord.is( null ) ) { 
		// Put in the Global ConcurrentHashMap if it exists in state.
		dataRecordByProductType.putAll(reparseDeviceData(state.deviceRecord)) // Store in the Global ConcurrentHashMap
	} else if ( state.deviceRecord.is( null ) && getDataRecordByProductType().deviceRecord ) {
		// Data record doesn't exist in state, but it is in the concurrentHashMap - So store in state rather than re-retrieve
		state.deviceRecord = dataRecordByProductType.deviceRecord
		updateDataValue("deviceModel", state.deviceRecord?.fingerprints?.name)
	} else if ( state.deviceRecord.is( null )) {
		// Data record doesn't exist - get it and store in the global data record
		Map createdRecord = openSmarthouseCreateDeviceDataRecord() 
        
        if (createdRecord) {
		    state.deviceRecord = createdRecord
		    updateDataValue("deviceModel", createdRecord?.fingerprints?.name)
            dataRecordByProductType.putAll(reparseDeviceData(createdRecord))
        }
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
    runIn(5, versionInfoTools_refreshVersionInfo)
	
	if (txtEnable) log.info "Device ${device.displayName}: Done Initializing."
    
    // schedule('0 */15 * ? * * *', refresh)
    unschedule()	
	
}

//////////////////////////////////////////////////
///////      Handle Parameter Report      ////////
//////////////////////////////////////////////////
// If a paramter gets changed in the parameterGetSendTools library, it can be handled by the driver here.

void handleParameterReport(hubitat.zwave.commands.configurationv2.ConfigurationReport  cmd) {
	if (logEnable) log.debug "Function handleParameterReport received the report ${cmd}."
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
		if (supportedClasses.contains(0x71) && notificationTools_refresh) 	notificationTools_refresh(ep)
		if (record.classes.contains(0x62)   && locktools_refresh) 		locktools_refresh(ep)
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

//////////////////////////////////////////////////////////////////////
//////                  Z-Wave Helper Functions                ///////
//////   Format messages, Send to Device, secure Messages      ///////
//////////////////////////////////////////////////////////////////////

void logsOff() {
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

////    Hail   ////
void zwaveEvent(hubitat.zwave.commands.hailv1.Hail cmd) {
	refresh()
}

