library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Handles Zwave Notifications",
        name: "notificationToolsV2",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "zwaveTools.hubTools",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/notificationTools.groovy"
)
/*
Relevant Z-Wave standards
SDS13713 = Silicon Labs Zwave Standard
*/
//////////////////////////////////////////////////////////////////////
//////        Handle   Notifications     ///////
//////////////////////////////////////////////////////////////////////


void	notificationTools_refresh(Integer ep = null ) {
	// A device can have many notification types, and they can change with parameter settings,'
	// So notifications aren't stable. Don't assume anything about the notifications. Query as needed.
	basicZwaveSend(zwave.notificationV8.notificationSupportedGet(), ep)
}
void zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationSupportedReport cmd, Integer ep = null ) {
log.debug "NotificationSupportedReport is: ${cmd} with payload values ${cmd.payload}"

log.debug "Supported notification Types are: ${getNotificationTypesList(cmd)}"
	getNotificationTypesList(cmd).each{it -> 
		basicZwaveSend(zwave.notificationV8.eventSupportedGet(notificationType:(Integer) it), ep)}
}

void zwaveEvent(hubitat.zwave.commands.notificationv8.EventSupportedReport cmd, Integer ep = null ){
log.debug "EventSupportedReport is: ${cmd}"

	List supportedEvents = cmd.supportedEvents.findAll{k, v -> ((v as Boolean) == true) }.collect{key, value -> (Integer) key  }
		
	performRefreshByType((int) cmd.notificationType, supportedEvents, ep)
}

void performRefreshByType(Integer type, List<Integer> events, Integer ep) {
	// type is a single integer item corresponding to Column B of zwave standard SDS13713
	//	Events is a list of integers identifying the sub-events for the type. This corresponds to column G of zwave standard SDS13713.
	events.each{ it ->
		basicZwaveSend(zwave.notificationV8.notificationGet(v1AlarmType:0, event:(Integer) it , notificationType: type), ep)
	}
}

List<Integer> getNotificationTypesList(def cmd) {
	List<Integer> notificationTypes = []
	
	if (cmd.smoke)				notificationTypes += 1 // Smoke
	if (cmd.co)					notificationTypes += 2 // CO
	if (cmd.co2)				notificationTypes += 3 // CO2
	if (cmd.heat)				notificationTypes += 4 // Heat
	if (cmd.water)				notificationTypes += 5 // Water
	if (cmd.accessControl) 		notificationTypes += 6 // Access Control
	if (cmd.burglar)			notificationTypes += 7 // Burglar
	if (cmd.powerManagement)	notificationTypes += 8 // Power Management
	if (cmd.system)				notificationTypes += 9 // System
	if (cmd.emergency)			notificationTypes += 10 // Emergency Alarm
	if (cmd.clock)				notificationTypes += 11 // Clock
	if (cmd.appliance)			notificationTypes += 12 // Appliance
	if (cmd.homeHealth)			notificationTypes += 13 // Home Health
	if (cmd.siren)				notificationTypes += 14 // Siren
	if (cmd.waterValve)			notificationTypes += 15 // Water Valve
	if (cmd.weatherAlarm)		notificationTypes += 16 // Weather Alarm
	if (cmd.irrigation)			notificationTypes += 17 // Irrigation
	if (cmd.gasAlarm)			notificationTypes += 18 // Gas Alarm
	if (cmd.pestControl)		notificationTypes += 19 // Pest Control
	if (cmd.lightSensor)		notificationTypes += 20 // Light Sensor
	if (cmd.waterQualityMonitoring)		notificationTypes += 21 // Water Quality
	if (cmd.homeMonitoring)		notificationTypes += 22 // Home Monitoring
	return notificationTypes
}

List<Map> getFormattedZWaveNotificationEvent(def cmd)
{
 
	log.debug "Notificatino Report is ${cmd}"
	
	Date currentDate = new Date()
	def notificationTypeGroup =
		[ 	1:[ // Smoke
				0:[
					1:[[name:"smoke" , value:"clear", descriptionText:"Smoke detected (location provided) status Idle."]],
					2:[[name:"smoke" , value:"clear", descriptionText:"Smoke detector status Idle."]],
					4:[[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."]],				
					5:[[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."]],				
					7:[[name:"consumableStatus" , value:"good", descriptionText:"Periodic Maintenance Not Due"]],				
					8:[[name:"consumableStatus" , value:"good", descriptionText:"No Dust in device - clear."]],
					], 
				1:[[name:"smoke" , value:"detected", descriptionText:"Smoke detected (location provided)."]], 
				2:[[name:"smoke" , value:"detected", descriptionText:"Smoke detected."]],
				4:[[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required."]],	
				5:[[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required (End-of-Life)."]],				
				7:[[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, periodic inspection."]],				
				8:[[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, dust in device."]],
				],
			2:[ // CO
				0:[
					1:[[name:"carbonMonoxide" , value:"clear", descriptionText:"Carbon Monoxide status."]],
					2:[[name:"carbonMonoxide" , value:"clear", descriptionText:"Carbon Monoxide status."]],	
					4:[[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."]],				
					5:[[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."]],				
					7:[[name:"consumableStatus" , value:"good", descriptionText:"Maintenance required cleared, periodic inspection."]],				
					], 
				1:[[name:"carbonMonoxide" , value:"detected", descriptionText:"Carbon Monoxide detected (location provided)."]], 
				2:[[name:"carbonMonoxide" , value:"detected", descriptionText:"Carbon Monoxide detected."]],
				4:[[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required (End-of-Life)."]],				
				5:[[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, periodic inspection."]],				
				7:[[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, dust in device."]],
				],
			3:[ // CO2
				0:[
					1:[[name:"carbonDioxideDetected" , value:"clear", descriptionText:"Carbon Dioxide status."]],
					2:[[name:"carbonDioxideDetected" , value:"clear", descriptionText:"Carbon Dioxide status."]],	
					4:[[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."]],				
					5:[[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."]],				
					7:[[name:"consumableStatus" , value:"good", descriptionText:"Maintenance (cleared)."]],				
					], 
				1:[[name:"carbonDioxideDetected" , value:"detected", descriptionText:"Carbon Dioxide detected (location provided)."]], 
				2:[[name:"carbonDioxideDetected" , value:"detected", descriptionText:"Carbon Dioxide detected."]],
				4:[[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required (End-of-Life)."]],				
				5:[[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, periodic inspection."]],				
				7:[[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, dust in device."]],
				],
			4:[ // Heat Alarm - requires custom attribute heatAlarm
				0:[
					1:[[name:"heatAlarm" , value:"normal", descriptionText:"Heat Alarm Notification, Status Normal."]],
					2:[[name:"heatAlarm" , value:"normal", descriptionText:"Heat Alarm Notification, Status Normal."]],
					5:[[name:"heatAlarm " , value:"normal", descriptionText:"Heat Alarm Notification, Status Normal."]],
					6:[[name:"heatAlarm " , value:"normal", descriptionText:"Heat Alarm Notification, Status Normal."]],
					8:[[name:"consumableStatus " , value:"good", descriptionText:"Replacement required (End-of-Life)."]],				
					10:[[name:"consumableStatus" , value:"good", descriptionText:"Maintenance (cleared)"]],				
					11:[[name:"consumableStatus" , value:"good", descriptionText:"Maintenance (cleared)"]],
					12:[[name:"heatAlarm " , value:"normal", descriptionText:"Heat Alarm Notification, Status Normal."]],
					13:[[name:"heatAlarm " , value:"normal", descriptionText:"Heat Alarm Notification, Status Normal."]],	
				], 
				1:[[name:"heatAlarm" , value:"overheat", descriptionText:"Overheat detected, Location Provided."]],
				2:[[name:"heatAlarm" , value:"overheat", descriptionText:"Overheat detected, Unknown Location."]],
				3:[[name:"heatAlarm " , value:"rapidRise", descriptionText:"Rapid Temperature Rise detected, Location Provided."]],
				4:[[name:"heatAlarm " , value:"rapidRise", descriptionText:"Rapid Temperature Rise detected, Unknown Location."]],				
				5:[[name:"heatAlarm " , value:"underheat", descriptionText:"Underheat detected, Location Provided."]],
				6:[[name:"heatAlarm " , value:"underheat", descriptionText:"Underheat detected, Unknown Location."]],
				8:[[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required (End-of-Life)."]],				
				10:[[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, periodic inspection."]],				
				11:[[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, dust in device."]],
				12:[[name:"heatAlarm " , value:"rapidFall", descriptionText:"Rapid Temperature Fall detected, Location Provided."]],
				13:[[name:"heatAlarm " , value:"rapidFall", descriptionText:"Rapid Temperature Fall detected, Unknown Location."]],				
				],				
			5:[ // Water	
				0:[
					1:[[name:"water" , value:"dry", descriptionText:"Water Alarm Notification, Status Dry."]],
					2:[[name:"water" , value:"dry", descriptionText:"Water Alarm Notification, Status Dry."]],
					5:[[name:"filterStatus " , value:"normal", descriptionText:"Water filter good."]],				
				], 
				1:[[name:"water" , value:"wet", descriptionText:"Water leak detected (location provided)."]], 
				2:[[name:"water" , value:"wet", descriptionText:"Water leak detected."]],
				5:[[name:"filterStatus " , value:"replace", descriptionText:"Replace water filter (End-of-Life)."]],				
				],

			6:[ // Access Control (Locks)
				1:[[name:"lock" , value:"locked", descriptionText:"Manual lock operation"]], 
                2:[[name:"lock" , value:"unlocked", descriptionText:"Manual unlock operation"]], 
				3:[[name:"lock" , value:"locked", descriptionText:"RF lock operation"]], 
				4:[[name:"lock" , value:"unlocked", descriptionText:"RF unlock operation"]], 
				5:[[name:"lock" , value:"locked", descriptionText:"Keypad lock operation"]], 
				6:[[name:"lock" , value:"unlocked", descriptionText:"Keypad unlock operation"]], 
				9:[[name:"lock" , value:"locked", descriptionText:"Auto lock locked operation"]], 
				10:[[name:"lock" , value:"unknown", descriptionText:"Auto lock not fully locked operation"]],
				11:[[name:"lock" , value:"unknown", descriptionText:"Lock jammed"]],
				12:[[name:"lockStatus" , value:"All codes deleted", descriptionText:"All user codes deleted."]],
				13:[[name:"lockStatus" , value:"Code deleted", descriptionText:"Single user code deleted."]],
				14:[[name:"lockStatus" , value:"Code added", descriptionText:"New user code added."]],
				15:[[name:"lockStatus" , value:"Duplicate code", descriptionText:"User code not added. Duplicate."]],
				16:[[name:"windowDoorStatus" , value:"open", descriptionText:"Window/door is open"]],
				17:[[name:"windoDoorStatus" , value:"closed", descriptionText:"Window/door is closed"]],
				18:[[name:"handleStatus" , value:"open", descriptionText:"Window/door handle is open"]],
				19:[[name:"handleStatus" , value:"closed", descriptionText:"Window/door handle is closed"]],
				22:[[name:"contact" , value:"open", descriptionText:"Door/Window open"]], 	
				23:[[name:"contact" , value:"closed", descriptionText:"Door/Window closed"]], 
				// 254:[[name:"lock" , value:"unknown", descriptionText:"Lock in unknown state"]],
				], 
			7:[ // Home Security
				0:[	 // These events "clear" a sensor.	
					1:[[name:"contact" , value:"closed", descriptionText:"Contact sensor, closed (location provided)"]], 
					2:[[name:"contact" , value:"closed", descriptionText:"Contact sensor, closed"]], 					
					3:[[name:"tamper" , value:"clear", descriptionText:"Tamper state cleared."]],
					4:[[name:"tamper" , value:"clear", descriptionText:"Tamper state cleared."]],
					5:[[name:"shock" , value:"clear", descriptionText:"Glass Breakage Not Detected (location provided)"]], // glass Breakage  attribute!
					6:[[name:"shock" , value:"clear", descriptionText:"Glass Breakage Not Detected"]], 	 // glass Breakage attribute!					
					7:[[name:"motion" , value:"inactive", descriptionText:"Motion Inactive."]],
					8:[[name:"motion" , value:"inactive", descriptionText:"Motion Inactive."]],
					9:[[name:"tamper" , value:"clear", descriptionText:"Tamper state cleared."]],
					], 
				1:[[name:"contact" , value:"open", descriptionText:"Contact sensor, open (location provided)"]], 	
				2:[[name:"contact" , value:"open", descriptionText:"Contact sensor, open"]], 					
				3:[[name:"tamper" , value:"detected", descriptionText:"Tampering 7, device cover removed"]], 
				4:[[name:"tamper" , value:"detected", descriptionText:"Tampering, invalid code."]], 
				5:[[name:"shock" , value:"detected", descriptionText:"Glass Breakage Detected (location provided)"]], 
				6:[[name:"shock" , value:"detected", descriptionText:"Glass Breakage Detected"]], 				
				7:[[name:"motion" , value:"active", descriptionText:"Motion detected (location provided)."]],
				8:[[name:"motion" , value:"active", descriptionText:"Motion detected."]],
				9:[[name:"tamper" , value:"detected", descriptionText:"Tampering, device moved"]]
				],
			8:[ // Power Management
				0:[ // These events "clear" a sensor
					5:[[name:"powerSource" , value:"unknown", descriptionText:"Voltage drop/drift cleared"]],
					],
				1:[[name:"powerSource" , value:"unknown", descriptionText:"Power applied"]],
				10:[[name:"powerSource" , value:"battery", descriptionText:"Replace battery soon"]],
				11:[[name:"powerSource" , value:"battery", descriptionText:"Replace battery now"]],
				],
			9:[ // System
				0:[ // These events "clear" a sensor
					4:[[name:"softwareFailure" , value:"cleared", descriptionText:"System Software Report - Startup Cleared"]],
					5:[[name:"heartbeat" , value:currentDate, descriptionText:"Last Heartbeat"]],
					6:[[name:"tamper" , value:"clear", descriptionText:"Tampering, device cover replaced"]], 
					],
				1:[[name:"hardwareFailure" , value:"unknown", descriptionText:"System Hardware Failure"]],
				2:[[name:"softwareFailure" , value:"unknown", descriptionText:"System Software Failure"]],
				3:[[name:"hardwareFailure" , value:(cmd.notificationStatus), descriptionText:"System Hardware Failure Report - Proprietary Code"]],
				4:[[name:"softwareFailure" , value:(cmd.notificationStatus), descriptionText:"System Software Failure Report - Proprietary Code"]],
				5:[[name:"heartbeat" , value:currentDate, descriptionText:"Last Heartbeat"]],
				6:[[name:"tamper" , value:"detected", descriptionText:"Tampering, device cover removed"]], 		
                ],
			10:[ //  Gas Alarm
				0:[	 // These events "clear" a sensor.	
					1:[[name:"naturalGas" , value:"clear", descriptionText:"Combustible gas (cleared) (location provided)"]], 	
					2:[[name:"naturalGas" , value:"clear", descriptionText:"Combustible gas  (cleared) "]], 
					5:[[name:"naturalGas" , value:"clear", descriptionText:"Gas detector test completed."]], 	
					6:[[name:"consumableStatus" , value:"good", descriptionText:"Gas detector (good)"]],
					], 
				1:[[name:"naturalGas" , value:"detected", descriptionText:"Combustible gas detected (location provided)"]], 	
				2:[[name:"naturalGas" , value:"detected", descriptionText:"Combustible gas detected"]], 
				5:[[name:"naturalGas" , value:"tested", descriptionText:"Gas detector test"]], 	
				6:[[name:"consumableStatus" , value:"replace", descriptionText:"Gas detector, replacement required"]],
				],				
			14:[ // Siren
				0:[
					1:[[name:"alarm" , value:"off", descriptionText:"Alarm Siren Off."]],
					], 
				1:[[name:"alarm" , value:"siren", descriptionText:"Alarm Siren On."]],
				], 
			15:[ // Water Valve
				0:[[name:"valve" , value:( (cmd.event > 0 ) ? "open" : "closed"), descriptionText:"Valve Operation."]], 
				1:[[name:"valve" , value:( (cmd.event > 0 ) ? "open" : "closed"), descriptionText:"Valve Operation."]],
				2:[[name:"valve" , value:( (cmd.event > 0 ) ? "open" : "closed"), descriptionText:"Master Valve Operation."]],				
				], 
			22:[ // Home Monitoring
				0:[
					1:[[name:"presence" , value:"not present", descriptionText:"Home not occupied"]],
					2:[[name:"presence" , value:"not present", descriptionText:"Home not occupied"]],
					], 
				1:[[name:"presence" , value:"present", descriptionText:"Home occupied (location provided)"]],  
				2:[[name:"presence" , value:"present", descriptionText:"Home occupied"]],
				]
				
		].get((int) cmd.notificationType)
		
		if (cmd.event == 0){
			if (cmd.eventParametersLength == 1) { // This is for clearing events.
				return notificationTypeGroup?.get((int) cmd.event )?.get( (int) cmd.properties1 )
			} else {
				log.error "In function getZWaveNotificationEvent(), received NotificationReport with eventParametersLength of unexpected size. Returning null. NotificationReport is ${cmd}"
				return null
			} 
		}		
		
		List<Map> notificationEvents = notificationTypeGroup?.get((int) cmd.event )
		if (logEnable) log.debug "notificationEvents is: ${notificationEvents}."
		
		return notificationEvents
}

void zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationReport cmd, Integer ep = null )
{
	List<Map> theseEvents =  getFormattedZWaveNotificationEvent(cmd)	
	if (!theseEvents) {
		log.warn "Device ${device.displayName}. No event generated for Notification command report ${cmd}"
		return
	}	

	getChildDeviceListByEndpoint(ep)?.each{ it.parse(theseEvents)}

	if ((ep ?: 0) == 0) { this.parse(theseEvents) }
}
