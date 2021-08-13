library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Tools to manage device parameters and other device-specific data.",
        name: "deviceManagementTools",
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
// reportQueues stores a map of SynchronousQueues. When requesting a report from a device, the report handler communicates the report back to the requesting function using a queue. This makes programming more like "synchronous" programming, rather than asynchronous handling.
// This is a map within a map. The first level map is by deviceNetworkId. Since @Field static variables are shared among all devices using the same driver code, this ensures that you get a unique second-level map for a particular device. The second map is keyed by the report class hex string. For example, if you want to wait for the configurationGet report, wait for "7006".
@Field static reportQueues = new ConcurrentHashMap<String, ConcurrentHashMap>(128)

SynchronousQueue myReportQueue(String reportClass) {
	ConcurrentHashMap thisDeviceQueues = reportQueues.get(device.deviceNetworkId, new ConcurrentHashMap<String,SynchronousQueue>(32))
	
	// Get the queue if it exists, create (new) it if it does not.
	SynchronousQueue thisReportQueue = thisDeviceQueues.get(reportClass, new SynchronousQueue())
	return thisReportQueue
}


/////////////////////////////////////////////////////////////////////////////////////// 
///////                   Parameter Updating and Management                    ////////
///////      Handle Update(), and Set, Get, and Process Parameter Values       ////////
/////////////////////////////////////////////////////////////////////////////////////// 

@Field static  ConcurrentHashMap<String, ConcurrentHashMap> allPendingParameterChanges = new ConcurrentHashMap<String, ConcurrentHashMap>()
@Field static  ConcurrentHashMap<String, ConcurrentHashMap> allDevicesParameterValues = new ConcurrentHashMap<String, ConcurrentHashMap>()

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


ConcurrentHashMap getPendingChangeMap() {
	return  allPendingParameterChanges.get(device.deviceNetworkId, new ConcurrentHashMap(32) )
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

library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Database of Device Characteristics",
        name: "ZwaveDeviceDatabase",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools"
)

Map getLocallyStoredDataRecord() {
	Integer mfr = getDataValue("manufacturer")?.toInteger()
	Integer Id = getDataValue("deviceId")?.toInteger()
	Integer Type = getDataValue("deviceType")?.toInteger()
	
	return deviceDatabase.find{ DBentry ->
					DBentry?.fingerprints.find{ subElement-> 
							((subElement.manufacturer == mfr) && (subElement.deviceId == Id) && (subElement.deviceType == Type )) 
						}
				}
}

@Field static List deviceDatabase = 
[
	[
		formatVersion:1,
		fingerprints: [
				[manufacturer:838, deviceId: 769,  deviceType:769, name:"Ring G2 Motion Sensor"]
				],
		classVersions:[0:1, 85:0, 89:1, 90:1, 94:1, 108:0, 112:1, 113:8, 114:1, 115:1, 122:1, 128:1, 133:2, 134:2, 135:3, 142:3, 159:0], 
		endpoints:[
				0:[ children:[[type:'Generic Component Motion Sensor', 'namespace':'hubitat', childName:"Motion Sensor"]],
					classes:[80, 85, 89, 90, 94, 108, 112, 113, 114, 115, 122, 128, 133, 134, 135, 142, 159], 
					notifications:[7:[3, 8], 8:[1, 5], 9:[4, 5]]]
				],
		deviceInputs:[
			1:[ size:1,	category:"advanced", name:"1", title:"(1) Heartbeat Interval", description:"Number of minutes between heartbeats.", range:"1..70",  type:"number" ],
			2:[ size:1,	category:"advanced", name:"2", title:"(2) Application Retries", description:"Number of application level retries attempted", range:"0..5", type:"number" ],
			3:[ size:1,	category:"advanced", name:"3", title:"(3) App Level Retry Base Wait", description:"Application Level Retry Base Wait Time Period (seconds)", range:"1..96", type:"number"],
			4:[ size:1,	category:"advanced", name:"4", title:"(4) LED Indicator Enable", description:"Configure the various LED indications on the device", options:[0:"Don’t show green", 1:"Show green after Supervision Report Intrusion (Fault)", 2:"Show green after Supervision Report both Intrusion and Intrusion clear"], type:"enum" ],
			5:[ size:1,	category:"advanced", name:"5", title:"(5) Occupancy Signal Clear", range:"0..255", type:"number"],
			6:[ size:1,	category:"advanced", name:"6", title:"(6) Intrusion Clear Delay", range:"0..255", type:"number"],
			7:[ size:1,	category:"advanced", name:"7", title:"(7) Standard Delay Time", range:"0..255", type:"number"],
			8:[ size:1,	category:"basic", name:"8", title:"(8) Motion Detection Mode", description:"Adjusts motion sensitivity, 0 = low ... 4 = high", range:"0..4", type:"number" ],
			9:[ size:1,	category:"advanced", name:"9", title:"(9) Lighting Enabled", range:"0..1", type:"number"],
			10:[size:1, category:"advanced", name:"10", title:"(10) Lighting Delay", description:"Delay to turn off lights when motion no longer detected", range:"0..60", type:"number"],
			11:[size:2, category:"advanced", name:"11", title:"(11) Supervisory Report Timeout", range:"500..30000", type:"number"]
		]
	],
	[
	formatVersion:1, 
	fingerprints:[['manufacturer':634, 'deviceId':18, 'deviceType':769, name:'Zooz: ZSE18']], 
	classVersions:[89:1, 48:2, 152:0, 0:1, 132:2, 122:1, 133:2, 112:1, 134:2, 113:5, 114:1, 115:1, 159:0, 90:1, 128:1, 108:0, 94:1, 85:0, 32:1], 
	endpoints:[
		0:[	children:[[type:'Generic Component Motion Sensor', 'namespace':'hubitat', childName:"Motion Sensor"]],
			classes:[0, 32, 48, 85, 89, 90, 94, 108, 112, 113, 114, 115, 122, 128, 132, 133, 134, 152, 159], 
			notifications:[7:[8, 9]]]
		], 
	deviceInputs:[
		12:[size:1, name:'12', description:' 1 = low sensitivity and 8 = high sensitivity.', range:'1..8', title:'(12)  PIR sensor sensitivity', type:'number'], 
		14:[size:1, name:'14', options:[0:'Disabled', 1:'Enabled'], title:'(14) BASIC SET reports', type:'enum'], 
		15:[size:1, name:'15', options:[0:'Send 255 on motion, 0 on clear (normal)', 1:'Send 0 on motion, 255 on clear (reversed)'], title:'(15) reverse BASIC SET', type:'enum'], 
		17:[size:1, name:'17', options:[0:'Disabled', 1:'Enabled'], title:'(17) vibration sensor', type:'enum'], 
		18:[size:2, name:'18', description:'3=6 seconds, 65535=65538 seconds (add 3 seconds to value set)', range:'3..65535', title:'(18) trigger interval', type:'number'], 
		19:[size:1, name:'19', options:[ 0:'Send Notification Reports to Hub', 1:'Send Binary Sensor Reports to Hub'], title:'(19) Report Type', type:'enum'], 		
		20:[size:1, name:'20', options:[0:'Disabled', 1:'Enabled'],title:'(20) LED Indicator', type:'enum'], 
		32:[size:1, name:'32', description:'percent battery left', range:'10..50', title:'(32) Low Battery Alert', type:'number']]
	],	
	[
		formatVersion:1,
		fingerprints: [
				[manufacturer:0x0184, 	deviceId: 0x3034,  deviceType:0x4447, name:"Dragon Tech WD100"],
				[manufacturer:0x000C, 	deviceId: 0x3034,  deviceType:0x4447, name:"HomeSeer WD100+"],
				[manufacturer:0x0315, 	deviceId: 0x3034,  deviceType:0x4447, name:"ZLink Products WD100+"],
				],
		classVersions: [89:1, 38:1, 39:1, 122:2, 133:2, 112:1, 134:2, 114:2, 115:1, 90:1, 91:1, 94:1, 32:1, 43:1],
		endpoints:[
				0:[ children:[	[type:'Generic Component Central Scene Dimmer', 'namespace':'hubitat', childName:"Dimmer 0"],
								[type:'Generic Component Central Scene Dimmer', 'namespace':'hubitat', childName:"Dimmer 1"]
								],
					classes:[94, 134, 114, 90, 133, 89, 115, 38, 39, 112, 44, 43, 91, 122]]
				],
		deviceInputs:[
			4:[ size:1,	category:"basic", name:"4", title:"(4) Orientation", description:"Control the on/off orientation of the rocker switch", options:[0:"Normal", 1:"Inverted"], type:"enum" ],

			7:[ size:1,	category:"basic", name:"7", title:"(7) Remote Dimming Level Increment", range:"1..99", type:"number"],
			8:[ size:2,	category:"basic", name:"8", title:"(8) Remote Dimming Level Duration", description:"Time interval (in tens of ms) of each brightness level change when controlled locally", range:"0..255", type:"number" ],
			9:[ size:1,	category:"basic", name:"9", title:"(9) Local Dimming Level Increment", range:"1..99", type:"number"],
			10:[size:2, category:"basic", name:"10", title:"(10) Local Dimming Level Duration", description:"Time interval (in tens of ms) of each brightness level change when controlled locally", range:"0..255", type:"number"]
		]
	],
	[
	
		formatVersion:1, 
		fingerprints: [
				[manufacturer:0x000C, 	deviceId: 0x3033,  deviceType:0x4447, name:"HomeSeer WS100 Switch"],
				],
		classVersions:[44:1, 89:1, 37:1, 39:1, 0:1, 122:1, 133:1, 112:1, 134:1, 114:1, 115:1, 90:1, 91:1, 94:1, 32:1, 43:1], 
		endpoints:[	
					0:[children:[ [type:'Generic Component Central Scene Dimmer', 'namespace':'hubitat']	],
						classes:[0, 32, 37, 39, 43, 44, 89, 90, 91, 94, 112, 114, 115, 122, 133, 134]]
				], 
		deviceInputs:[
			3:[ size:1, level:"basic", name:"3", title:"(3) LED Indication Configuration", options:[0:"LED On when device is Off", 1:"LED On when device is On", 2:"LED always Off", 4:"LED always On"], type:"enum" ],
			4:[ size:1, level:"advanced", name:"4", title:"(4) Orientation", description:"Controls the on/off orientation of the rocker switch", options:[0:"Normal", 1:"Inverted"], type:"enum"],
		]
	],
	[
		formatVersion:1, 
		fingerprints:[[manufacturer:12, deviceId:12342, deviceType:17479, name:'HomeSeer Technologies: HS-WD200+']], 
		classVersions:[44:1, 89:1, 38:3, 39:1, 0:1, 122:1, 133:2, 112:1, 134:2, 114:1, 115:1, 90:1, 91:3, 94:1, 32:1, 43:1], 
		endpoints:[
					0:[	children:[ [type:'Generic Component Central Scene Dimmer', 'namespace':'hubitat']	],
						classes:[0, 32, 38, 39, 43, 44, 89, 90, 91, 94, 112, 114, 115, 122, 133, 134]]
				], 
		deviceInputs:[ // Firmware 5.14 and above!
			3:[size:1, name:'3', options:['0':'Bottom LED ON if load is OFF', '1':'Bottom LED OFF if load is OFF'], title:'(3) Bottom LED Operation', type:'enum'], 
			4:[size:1, name:'4', options:['0':'Normal - Top of Paddle turns load ON', '1':'Inverted - Bottom of Paddle turns load ON'], title:'(4) Paddle Orientation', type:'enum'], 
			5:[size:1, name:'5', options:['0':'(0) No minimum set', '1':'(1) 6.5%', '2':'(2) 8%', '3':'(3) 9%', '4':'(4) 10%', '5':'(5) 11%', '6':'(6) 12%', '7':'(7) 13%', '8':'(8) 14%', '9':'(9) 15%', '10':'(10) 16%', '11':'(11) 17%', '12':'(12) 18%', '13':'(13) 19%', '14':'(14) 20%'], title:'(5) Minimum Dimming Level', type:'enum'],
 			6:[size:1, name:'6', options:['0':'Central Scene Enabled', '1':'Central Scene Disabled'], title:'(5) Central Scene Enable/Disable', type:'enum'], 			
			11:[size:1, name:'11', range:'0..90', title:'(11) Set dimmer ramp rate for remote control (seconds)', type:'number'], 
			12:[size:1, name:'12', range:'0..90', title:'(12) Set dimmer ramp rate for local control (seconds)', type:'number'], 
			13:[size:1, name:'13', options:['0':'LEDs show load status', '1':'LEDs display a custom status'], description:'Set dimmer display mode', title:'(13) Status Mode', type:'enum'], 	
			14:[size:1, name:'14', options:['0':'White', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan'], title:'(14) Set the LED color when displaying load status', type:'enum'], 		
			21:[size:1, name:'21', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], title:'(21) Status LED 1 Color (bottom LED)', type:'enum'],
			22:[size:1, name:'22', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], title:'(22) Status LED 2 Color', type:'enum'], 
			23:[size:1, name:'23', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], title:'(23) Status LED 3 Color', type:'enum'], 

			24:[size:1, name:'24', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], title:'(24) Status LED 4 Color', type:'enum'], 

			25:[size:1, name:'25', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], title:'(25) Status LED 5 Color', type:'enum'], 
			26:[size:1, name:'26', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], title:'(26) Status LED 6 Color', type:'enum'], 
			27:[size:1, name:'27', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], title:'(27) Status LED 7 Color (top LED)', type:'enum'], 
			30:[size:1, name:'30', range:'0..255', title:'(30) Blink Frequency when displaying custom status', type:'number'], 
			31:[size:1, name:'31', type:'number', title:'(31) LED 7 Blink Status - bitmap', description:'bitmap defines LEDs to blink '], 

		]
	],
	[
		formatVersion:1, 
		fingerprints:[
			[manufacturer:12, deviceId:12341, deviceType:17479, name:'HomeSeer Technologies: HS-WS200+']
			], 
		classVersions:[44:1, 89:1, 37:1, 39:1, 0:1, 122:1, 133:2, 112:1, 134:2, 114:1, 115:1, 90:1, 91:3, 94:1, 32:1, 43:1], 
		endpoints:[
					0:[	children:[ [type:'Generic Component Central Scene Switch', 'namespace':'hubitat']	],
						classes:[0, 32, 37, 39, 43, 44, 89, 90, 91, 94, 112, 114, 115, 122, 133, 134]]
				], 
		deviceInputs:[
			3:[size:1, name:'3', options:[0:'LED ON if load is OFF', '1':'LED OFF if load is OFF'], description:'Sets LED operation (in normal mode)', title:'(3) Bottom LED Operation', type:'enum'], 
			4:[size:1, name:'4', options:[0:'Top of Paddle turns load ON', '1':'Bottom of Paddle turns load ON'], description:'Sets paddle’s load orientation', title:'(4) Orientation', type:'enum'], 
			6:[size:1, name:'6', options:[0:'Disabled', '1':'Enabled'], description:'Enable or Disable Scene Control', title:'(6) Scene Control', type:'enum'], 
			13:[size:1, name:'13', options:[0:'Normal mode (load status)', '1':'Status mode (custom status)'], description:'Sets switch mode of operation', title:'(13) Status Mode', type:'enum'], 
			14:[size:1, name:'14', options:['0':'White', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan'], description:'Sets the Normal mode LED color', title:'(14) Load Status LED Color', type:'enum'], 
			21:[size:1, name:'21', options:['0':'Off', '1':'Red', '2':'Green', '3':'Blue', '4':'Magenta', '5':'Yellow', '6':'Cyan', '7':'White'], description:'Sets the Status mode LED color', title:'(21) Status LED Color', type:'enum'],
			31:[size:1, name:'31', description:'Sets the switch LED Blink frequency', range:'0..255', title:'(31) Blink Frequency', type:'number'],
		]
	],
	[
		formatVersion:1, 
		fingerprints:[
			[manufacturer:99, deviceId:12597, deviceType:18770, name:'Jasco Products: 46201']
			], 
		classVersions:[44:1, 34:1, 89:1, 37:1, 0:1, 122:1, 133:2, 112:1, 134:2, 114:1, 115:1, 90:1, 91:3, 94:1, 32:1, 43:1], 
		endpoints:[
				0:[	children:[ [type:'Generic Component Central Scene Switch', 'namespace':'hubitat']	],
					classes:[0, 32, 34, 37, 43, 44, 89, 90, 91, 94, 112, 114, 115, 122, 133, 134]]
			], 
		deviceInputs:[
			3:[size:1, name:'3', range:'0..255', title:'(3) Blue LED Night Light', type:'number']
		]
	],
	[
		formatVersion:1, 
		fingerprints:[[manufacturer:99, deviceId:12338, deviceType:20292, name:'GE/Jasco Heavy Duty Switch 14285']], 
		classVersions:[44:1, 89:1, 37:1, 0:1, 122:1, 133:2, 112:1, 134:2, 114:1, 115:1, 90:1, 91:3, 50:3, 94:1, 32:1, 43:1], 
		endpoints:[
				0:[	children:[ [type:'Generic Component Central Scene Switch', 'namespace':'hubitat']	],
					classes:[0, 32, 37, 43, 44, 50, 89, 90, 91, 94, 112, 114, 115, 122, 133, 134], 
					metersSupported:[0, 2, 4, 5, 6]]
			], 
		deviceInputs:[
			1:[size:1, name:'1', options:['0':'Return to last state', '1':'Return to off', '2':'Return to on'], title:'(1) Product State after Power Reset', type:'enum'], 
			2:[size:1, name:'2', options:['0':'Once monthly', '1':'Reports based on Parameter 3 setting', '2':'Once daily'], title:'(2) Energy Report Mode', type:'enum'], 
			3:[size:1, name:'3', range:'5..60', title:'(3) Energy Report Frequency', type:'number'], 
			19:[size:1, name:'19', options:['0':'Default', '1':'Alternate Exclusion (3 button presses)'], title:'(19) Alternate Exclusion', type:'enum']
			]
	],
	[
		formatVersion:1, 
		fingerprints:[
				[manufacturer:634, deviceId:40963, deviceType:40960, name:'Zooz: ZEN25']
			], 
		classVersions:[0:1, 32:1, 37:1, 50:3, 89:1, 90:1, 94:1, 96:2, 112:1, 113:8, 114:1, 115:1, 122:1, 133:2, 134:2, 142:3], 
		endpoints:[
				0:[	classes:[0, 32, 37, 50, 89, 90, 94, 96, 112, 113, 114, 115, 122, 133, 134, 142]], 
				1:[ children:[[type:'Generic Component Metering Switch', 'namespace':'hubitat', childName:"ZEN25 Left Outlet"]], 
					classes:[32, 37, 50, 89, 94, 133, 142], 
					metersSupported:[0, 2, 4, 5]], 
				2:[ children:[[type:'Generic Component Metering Switch', 'namespace':'hubitat', childName:"ZEN25 Right Outlet"]], 
					classes:[32, 37, 50, 89, 94, 133, 142], 
					metersSupported:[0, 2, 4, 5]], 
				3:[ children:[[type:'Generic Component Metering Switch', 'namespace':'hubitat', childName:"ZEN25 USB Port"]], 
					classes:[32, 37, 50, 89, 94, 133, 142]]
			], 
		deviceInputs:[
				1:[name:'1', title:'(1) On/Off After Power', size:1, description:'On/Off Status After Power Failure', type:'enum', options:[0:'Previous State', 1:'On', 2:'Off']], 
				2:[name:'2', title:'(2) Wattage Threshold', size:4, description:'Power Wattage Report Value Threshold', type:'number', range:0..65535], 
				3:[name:'3', title:'(3) Wattage Frequency', size:4, description:'Power Wattage Report Frequency', type:'number', range:30..2678400], 
				4:[name:'4', title:'(4) Energy Frequency', size:4, description:'Energy (kWh) Report Frequency', type:'number', range:30..2678400], 
				5:[name:'5', title:'(5) Voltage Frequency', size:4, description:'Voltage (V) Report Frequency', type:'number', range:30..2678400], 
				6:[name:'6', title:'(6) Current Frequency', size:4, description:'Electrical Current (A) Report Frequency', type:'number', range:30..2678400], 
				7:[name:'7', title:'(7) Overload Protection', size:1, type:'number', range:1..10], 
				8:[name:'8', title:'(8) Enable Auto-Off (Left)', size:1, description:'Enable Auto Turn-Off Timer for Left Outlet', type:'enum', options:[0:'Disable', 1:'Enable']], 
				9:[name:'9', title:'(9) Turn-Off Time, Minutes (Left)', size:4, description:'Auto Turn-Off Time for Left Outlet', type:'number', range:1..65535], 
				10:[name:'10', title:'(10) Enable Auto-On (Left)', size:1, description:'Enable Auto Turn-On Timer for Left Outlet', type:'enum', options:[0:'Disable', 1:'Enable']], 
				11:[name:'11', title:'(11) Turn-On Time, Minutes (Left)', size:4, description:'Auto Turn-On Time for Left Outlet', type:'number', range:1..65535], 
				12:[name:'12', title:'(12) Enable Auto-Off (Right)', size:1, description:'Enable Auto Turn-Off Timer for Right Outlet', type:'enum', options:[0:'Disable', 1:'Enable']], 
				13:[name:'13', title:'(13) Turn-Off Time, Minutes (Right)', size:4, description:'Auto Turn-Off Time for Right Outlet', type:'number', range:1..65535], 
				14:[name:'14', title:'(14) Enable Auto-On (Right)', size:1, description:'Enable Auto Turn-On Timer for Right Outlet', type:'enum', options:[0:'Disable', 1:'Enable']], 
				15:[name:'15', title:'(15) Turn-On Time, Minutes (Right)', size:4, description:'Auto Turn-On Time for Right Outlet', type:'number', range:1..65535], 
				16:[name:'16', title:'(16) Manual Control', size:1, description:'Enable/Disable Manual Control', type:'enum', options:[0:'Disable', 1:'Enable']], 
				17:[name:'17', title:'(17) LED Mode', size:1, description:'LED Indicator Mode', type:'enum', options:[0:'Always On', 1:'Follow Outlet', 2:'Momentary', 3:'Always Off']], 
				18:[name:'18', title:'(18) Reports', size:1, description:'Enable/Disable Energy and USB Reports', type:'enum', options:[0:'0 - Energy and USB reports enabled ', 1:'1 - Energy and USB reports disabled', 2:'2 - Energy reports for left outlet disabled', 3:'3 - Energy reports for right outlet disabled', 4:'4 - USB reports disabled']]
			]
	],	
	[
		formatVersion:1,
		fingerprints: [
				[manufacturer:0x031E, deviceId: 0x0001,  deviceType:0x000E, name:"Inovelli LZW36 Light / Fan Controller"]
				],
		classVersions: [32:1, 34:1, 38:3, 50:3, 89:1, 90:1, 91:3, 94:1, 96:2, 112:1, 114:1, 115:1, 117:2, 122:1, 133:2, 134:2, 135:3, 142:3, 152:1],
		endpoints:[ // List of classes for each endpoint. Key classes:  0x25 (37) or 0x26 (38), 0x32 (50), 0x70  (switch, dimmer, metering, 
					0:[ classes:[32, 34, 38, 50, 89, 90, 91, 94, 96, 112, 114, 115, 117, 122, 133, 134, 135, 142, 152]],
					1:[	children:[[type:"Generic Component Dimmer", namespace:"hubitat", childName:"LZW36 Dimmer Device"]], 
						classes:[0x26] ], 
					2:[	children:[[type:"Generic Component Fan Control", namespace:"hubitat", childName:"LZW36 Fan Device"]], 
						classes:[0x26] ]
				],
		deviceInputs:[
				1:[size:1, name:"1", range:"0..99", title:"(1) Light Dimming Speed (Remote)", type:"number"], 
				2:[size:1, name:"2", range:"0..99", title:"(2) Light Dimming Speed (From Switch)", type:"number"], 
				3:[size:1, name:"3", range:"0..99", title:"(3) Light Ramp Rate (Remote)", type:"number"], 
				4:[size:1, name:"4", range:"0..99", title:"(4) Light Ramp Rate (From Switch)", type:"number"], 
				5:[size:1, name:"5", range:"1..45", title:"(5) Minimum Light Level", type:"number"], 
				6:[size:1, name:"6", range:"55..99", title:"(6) Maximum Light Level", type:"number"], 
				7:[size:1, name:"7", range:"1..45", title:"(7) Minimum Fan Level", type:"number"], 
				8:[size:1, name:"8", range:"55..99", title:"(8) Maximum Fan Level", type:"number"], 
				10:[size:2, name:"10", range:"0..32767", title:"(10) Auto Off Light Timer", type:"number"], 
				11:[size:2, name:"11", range:"0..32767", title:"(11) Auto Off Fan Timer", type:"number"], 
				12:[size:1, name:"12", range:"0..99", title:"(12) Default Light Level (Local)", type:"number"], 
				13:[size:1, name:"13", range:"0..99", title:"(13) Default Light Level (Z-Wave)", type:"number"], 
				14:[size:1, name:"14", range:"0..99", title:"(14) Default Fan Level (Local)", type:"number"], 
				15:[size:1, name:"15", range:"0..99", title:"(15) Default Fan Level (Z-Wave)", type:"number"], 
				16:[size:1, name:"16", range:"0..100", title:"(16) Light State After Power Restored", type:"number"], 
				17:[size:1, name:"17", range:"0..100", title:"(17) Fan State After Power Restored", type:"number"], 
				18:[size:2, name:"18", range:"0..255", title:"(18) Light LED Indicator Color", type:"number"], 
				19:[size:1, name:"19", range:"0..10", title:"(19) Light LED Strip Intensity", type:"number"], 
				20:[size:2, name:"20", range:"0..255", title:"(20) Fan LED Indicator Color", type:"number"], 
				21:[size:1, name:"21", range:"0..10", title:"(21) Fan LED Strip Intensity", type:"number"],
				22:[size:1, name:"22", range:"0..10", title:"(22) Light LED Strip Intensity (When OFF)", type:"number"], 
				23:[size:1, name:"23", range:"0..10", title:"(23) Fan LED Strip Intensity (When OFF)", type:"number"], 
				24:[size:4, name:"24", range:"0..83823359", title:"(24) Light LED Strip Effect", type:"number"], 
				25:[size:4, name:"25", range:"0..83823359", title:"(25) Fan LED Strip Effect", type:"number"], 
				26:[size:1, name:"26", options:[0:"Stay Off", 1:"One Second", 2:"Two Seconds", 3:"Three Seconds", 4:"Four Seconds", 5:"Five Seconds", 6:"Six Seconds", 7:"Seven Seconds", 8:"Eight Seconds", 9:"Nine Seconds", 10:"Ten Seconds"], title:"(26) Light LED Strip Timeout", type:"enum"], 
				27:[size:1, name:"27", options:[0:"Stay Off", 1:"One Second", 2:"Two Seconds", 3:"Three Seconds", 4:"Four Seconds", 5:"Five Seconds", 6:"Six Seconds", 7:"Seven Seconds", 8:"Eight Seconds", 9:"Nine Seconds", 10:"Ten Seconds"], title:"(27) Fan LED Strip Timeout", type:"enum"], 
				28:[size:1, name:"28", range:"0..100", title:"(28) Active Power Reports", type:"number"], 
				29:[size:2, name:"29", range:"0..32767", title:"(29) Periodic Power & Energy Reports", type:"number"], 
				30:[size:1, name:"30", range:"0..100", title:"(30) Energy Reports", type:"number"], 
				31:[size:1, name:"31", options:[0:"None", 1:"Light Button", 2:"Fan Button", 3:"Both Buttons"], title:"(31) Local Protection Settings", type:"enum"], 
				51:[size:1, name:"51", description:"Disable the 700ms Central Scene delay.", title:"(51) Enable instant on", options:[0:"No Delay (Central scene Disabled)", 1:"700mS Delay (Central Scene Enabled)"], type:"enum"], 
		]
	],
	[
		formatVersion:1, 'fingerprints':[['manufacturer':634, 'deviceId':40961, 'deviceType':40960, name:'Zooz: ZEN26']], 
		classVersions:[89:1, 37:1, 142:3, 0:1, 122:1, 133:2, 112:1, 134:2, 114:1, 115:1, 90:1, 91:3, 94:1, 32:1], 
		endpoints:[ 0:[
						children:[ [type:'Generic Component Central Scene Switch', 'namespace':'hubitat']	],
						classes:[0, 32, 37, 89, 90, 91, 94, 112, 114, 115, 122, 133, 134, 142]]
				], 
		deviceInputs:[
			11:[size:1, name:'11', options:['0':'Local control disabled', '1':'Local control enabled'], description:'Enable or disable local ON/OFF control', title:'(11) Enable/disable paddle control', type:'enum'], 
			1:[size:1, name:'1', description:'Choose paddle functionality (invert)', range:'0..1', title:'(1) Paddle control', type:'number'], 
			2:[size:1, name:'2', options:['0':'LED ON when switch OFF', '1':'LED ON when switch ON', '2':'LED OFF', '3':'LED ON'], description:'Change behavior of the LED indicator', title:'(2) LED indicator control', type:'enum'], 
			3:[size:1, name:'3', options:['0':'Disable', '1':'Enable'], description:'Enable/disable turn-OFF timer', title:'(3) Auto turn-OFF timer', type:'enum'], 
			4:[size:4, name:'4', description:'Length of time before switch turns OFF', range:'0..65535', title:'(4) Auto turn-OFF timer length', type:'number'], 
			5:[size:1, name:'5', options:['0':'Disable', '1':'Enable'], description:'Enable/disable turn-ON timer', title:'(5) Auto turn-ON timer', type:'enum'], 
			6:[size:4, name:'6', description:'Length of time before switch turns ON', range:'0..65535', title:'(6) Auto turn-ON timer length', type:'number'], 
			7:[size:1, name:'7', options:['11':'Physical tap ZEN26, 3-way switch or timer', '12':'Z-Wave command or timer', '13':'Physical tap ZEN26, Z-Wave or timer', '14':'Physical tap ZEN26, 3-way switch, Z-Wave or timer', '15':'All of the above', '0':'None', '1':'Physical tap ZEN26 only', '2':'Physical tap 3-way switch only', '3':'Physical tap ZEN26 or 3-way switch', '4':'Z-Wave command', '5':'Physical tap ZEN26 or Z-Wave', '6':'Physical tap 3-way switch or Z-Wave', '7':'Physical tap ZEN26, 3-way switch or Z-Wave', '8':'Timer only', '9':'Physical tap ZEN26 or timer', '10':'Physical tap 3-way switch or timer'], title:'(7) Association reports', type:'enum'], 
			8:[size:1, name:'8', options:['0':'OFF', '1':'ON', '2':'Restore last state'], description:'Set the ON/OFF status for the switch after power failure', title:'(8) ON/OFF status after power failure', type:'enum'], 
			10:[size:1, name:'10', options:['0':'Scene control disabled', '1':'Scene control enabled'], title:'(10) Enable/disable scene control', type:'enum']
		]
	],	
	[
		formatVersion:1, 
		fingerprints:[
				['manufacturer':634, 'deviceId':40968, 'deviceType':40960, name:'Zooz: ZEN30'] // Zooz Zen 30
			], 
		classVersions:[0:1, 32:1, 37:1, 38:3, 89:1, 90:1, 91:3, 94:1, 96:2, 112:1, 114:1, 115:1, 122:1, 133:2, 134:2, 142:3, 152:2], 
		endpoints:[
					0:[
						children:[ [type:'Generic Component Central Scene Dimmer', 'namespace':'hubitat']	],
						classes:[0, 32, 37, 38, 89, 90, 91, 94, 96, 112, 114, 115, 122, 133, 134, 142, 152]], 
					1:[ children:[[type:'Generic Component Switch', namespace:'hubitat', childName:"Relay Switch"]], 
						classes:[32, 37, 89, 94, 133, 142]]
				], 
		deviceInputs:[
			1:[name:'1', title:'(1) LED Indicator Mode for Dimmer', size:1, type:'enum', options:[0:'ON when switch is OFF and OFF when switch is ON', 1:'ON when switch is ON and OFF when switch is OFF', 2:'LED indicator is always OFF', 3:'LED indicator is always ON']], 
			2:[name:'2', title:'(2) LED Indicator Control for Relay', size:1, type:'enum', options:[0:'ON when relay is OFF and OFF when relay is ON', 1:'ON when relay is ON and OFF when relay is OFF', 2:'LED indicator is always OFF', 3:'LED indicator is always ON']], 
			3:[name:'3', title:'(3) LED Indicator Color for Dimmer', size:1, description:'Choose the color of the LED indicators for the dimmer', type:'enum', options:[0:'White (default)', 1:'Blue', 2:'Green', 3:'Red']], 
			4:[name:'4', title:'(4) LED Indicator Color for Relay', size:1, type:'enum', options:[0:'White (default)', 1:'Blue', 2:'Green', 3:'Red']], 
			5:[name:'5', title:'(5) LED Indicator Brightness for Dimmer', size:1, type:'enum', options:[0:'Bright (100%)', 1:'Medium (60%)', 2:'Low (30% - default)']], 
			6:[name:'6', title:'(6) LED Indicator Brightness for Relay', size:1, type:'enum', options:[0:'Bright (100%)', 1:'Medium (60%)', 2:'Low (30% - default)']], 
			7:[name:'7', title:'(7) LED Indicator Mode for Scene Control', size:1, type:'enum', options:[0:'Enabled to indicate scene triggers', 1:'Disabled to indicate scene triggers (default)']], 
			8:[name:'8', title:'(8) Auto Turn-Off Timer for Dimmer', size:4, type:'number', range:"0..65535"], 
			9:[name:'9', title:'(9) Auto Turn-On Timer for Dimmer', size:4, type:'number', range:"0..65535"], 
			10:[name:'10', title:'(10) Auto Turn-Off Timer for Relay', size:4, type:'number', range:"0..65535"], 
			11:[name:'11', title:'(11) Auto Turn-On Timer for Relay', size:4, type:'number', range:"0..65535"], 
			12:[name:'12', title:'(12) On Off Status After Power Failure', size:1, type:'enum', options:[0:'Dimmer and relay forced to OFF', 1:'Dimmer forced to OFF, relay forced to ON', 2:'Dimmer forced to ON, relay forced to OFF', 3:'Restores status for dimmer and relay (default)', 4:'Restores status for dimmer, relay forced to ON', 5:'Restores status for dimmer, relay forced to OFF', 6:'Dimmer forced to ON, restores status for relay', 7:'Dimmer forced to OFF, restores status for relay', 8:'Dimmer and relay forced to ON']], 
			13:[name:'13', title:'(13) Ramp Rate Control for Dimmer', size:1, type:'number', range:"0..99"], 
			14:[name:'14', title:'(14) Minimum Brightness', size:1, type:'number', range:"1..99"], 
			15:[name:'15', title:'(15) Maximum Brightness', size:1, type:'number', range:"1..99"], 
			17:[name:'17', title:'(17) Double Tap Function for Dimmer', size:1, type:'enum', options:[0:'ON to full brightness with double tap (default)', 1:'ON to brightness set in #15 with double tap']], 
			18:[name:'18', title:'(18) Disable Double Tap', size:1, type:'enum', options:[0:'Full/max brightness level enabled (default)', 1:'Disabled, single tap for last brightness', 2:'Disabled, single tap to full brightness']], 
			19:[name:'19', title:'(19) Smart Bulb Setting', size:1, description:'Enable/Disable Load Control for Dimmer', type:'enum', options:[0:'Manual control disabled', 1:'Manual control enabled (default)', 2:'Manual and Z-Wave control disabled']], 
			20:[name:'20', title:'(20) Remote Control Setting', size:1, description:'Enable/Disable Load Control for Relay', type:'enum', options:[0:'Manual control disabled', 1:'Manual control enabled (default)', 2:'Manual and Z-Wave control disabled']], 
			21:[name:'21', title:'(21) Manual Dimming Speed', size:1, type:'number', range:"1..99"], 
			// 22:[name:'22', title:'(22) Z-Wave Ramp Rate for Dimmer', size:1, type:'enum', options:[0:'Match #13', 1:'Set through Command Class']], 
			23:[name:'23', title:'(23) Default Brightness Level ON for Dimmer', size:1, type:'number', range:"0..99"]	
		]
	]	
]
