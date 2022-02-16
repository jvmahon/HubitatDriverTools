library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Handles Zwave Notifications",
        name: "notificationTools",
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
	Map specifiedNotifications = getEndpointNotificationsSupported(ep)
	if (specifiedNotifications)
	{
		specifiedNotifications.each{type, events ->
				if ((int) type > 0) { performRefreshByType(type, events, ep) }
				}
	}	else  {
		basicZwaveSend(zwave.notificationV8.notificationSupportedGet(), ep)
	}
}

void zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationSupportedReport cmd, Integer ep = null ) {

	getNotificationTypesList(cmd).each{it -> 
		basicZwaveSend(zwave.notificationV8.eventSupportedGet(notificationType:(Integer) it), ep)}
}

void zwaveEvent(hubitat.zwave.commands.notificationv8.EventSupportedReport cmd, Integer ep = null ){

	List supportedEvents = cmd.supportedEvents.findAll{k, v -> ((v as Boolean) == true) }.collect{key, value -> (Integer) key  }
getEndpointNotificationsSupported(ep).put( (Integer) cmd.notificationType , supportedEvents)
	
	performRefreshByType((int) cmd.notificationType, supportedEvents, ep)
}

void performRefreshByType(Integer type, List<Integer> events, Integer ep) {
	// type is a single integer item corresponding to Column B of zwave standard SDS13713
	//	Events is a list of integers identifying the sub-events for the type. This corresponds to column G of zwave standard SDS13713.
	events.each{ it ->
		basicZwaveSend(zwave.notificationV8.notificationGet(v1AlarmType:0, event:(Integer) it , notificationType: type), ep)
	}
}


List<Integer> getNotificationTypesList(hubitat.zwave.commands.notificationv8.NotificationSupportedReport cmd) {
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

List<Map> getFormattedZWaveNotificationEvent(hubitat.zwave.commands.notificationv8.NotificationReport cmd)
{
	Date currentDate = new Date()
	def notifications =
		[ 	
			// Smoke
				1000:[
					1:[[name:"smoke" , value:"clear", descriptionText:"Smoke detected (location provided) status Idle."]],
					2:[[name:"smoke" , value:"clear", descriptionText:"Smoke detector status Idle."]],
					4:[[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."]],				
					5:[[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."]],				
					7:[[name:"consumableStatus" , value:"good", descriptionText:"Periodic Maintenance Not Due"]],				
					8:[[name:"consumableStatus" , value:"good", descriptionText:"No Dust in device - clear."]],
					], 
				1001:[[name:"smoke" , value:"detected", descriptionText:"Smoke detected (location provided)."]], 
				1002:[[name:"smoke" , value:"detected", descriptionText:"Smoke detected."]],
				1004:[[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required."]],	
				1005:[[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required (End-of-Life)."]],				
				1007:[[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, periodic inspection."]],				
				1008:[[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, dust in device."]],
			
			// CO
				2000:[
					1:[[name:"carbonMonoxide" , value:"clear", descriptionText:"Carbon Monoxide status."]],
					2:[[name:"carbonMonoxide" , value:"clear", descriptionText:"Carbon Monoxide status."]],	
					4:[[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."]],				
					5:[[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."]],				
					7:[[name:"consumableStatus" , value:"good", descriptionText:"Maintenance required cleared, periodic inspection."]],				
					], 
				2001:[[name:"carbonMonoxide" , value:"detected", descriptionText:"Carbon Monoxide detected (location provided)."]], 
				2002:[[name:"carbonMonoxide" , value:"detected", descriptionText:"Carbon Monoxide detected."]],
				2004:[[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required (End-of-Life)."]],				
				2005:[[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, periodic inspection."]],				
				2007:[[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, dust in device."]],
			
			// CO2
				3000:[
					1:[[name:"carbonDioxideDetected" , value:"clear", descriptionText:"Carbon Dioxide status."]],
					2:[[name:"carbonDioxideDetected" , value:"clear", descriptionText:"Carbon Dioxide status."]],	
					4:[[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."]],				
					5:[[name:"consumableStatus " , value:"good", descriptionText:"Replacement (cleared)."]],				
					7:[[name:"consumableStatus" , value:"good", descriptionText:"Maintenance (cleared)."]],				
					], 
				3001:[[name:"carbonDioxideDetected" , value:"detected", descriptionText:"Carbon Dioxide detected (location provided)."]], 
				3002:[[name:"carbonDioxideDetected" , value:"detected", descriptionText:"Carbon Dioxide detected."]],
				3004:[[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required (End-of-Life)."]],				
				3005:[[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, periodic inspection."]],				
				3007:[[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, dust in device."]],
			
			// Heat Alarm - requires custom attribute heatAlarm
				4000:[
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
				4001:[[name:"heatAlarm" , value:"overheat", descriptionText:"Overheat detected, Location Provided."]],
				4002:[[name:"heatAlarm" , value:"overheat", descriptionText:"Overheat detected, Unknown Location."]],
				4003:[[name:"heatAlarm " , value:"rapidRise", descriptionText:"Rapid Temperature Rise detected, Location Provided."]],
				4004:[[name:"heatAlarm " , value:"rapidRise", descriptionText:"Rapid Temperature Rise detected, Unknown Location."]],				
				4005:[[name:"heatAlarm " , value:"underheat", descriptionText:"Underheat detected, Location Provided."]],
				4006:[[name:"heatAlarm " , value:"underheat", descriptionText:"Underheat detected, Unknown Location."]],
				4008:[[name:"consumableStatus " , value:"replace", descriptionText:"Replacement required (End-of-Life)."]],				
				4010:[[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, periodic inspection."]],				
				4011:[[name:"consumableStatus" , value:"maintenance_required", descriptionText:"Maintenance required, dust in device."]],
				4012:[[name:"heatAlarm " , value:"rapidFall", descriptionText:"Rapid Temperature Fall detected, Location Provided."]],
				4013:[[name:"heatAlarm " , value:"rapidFall", descriptionText:"Rapid Temperature Fall detected, Unknown Location."]],				
			
			// Water	
				5000:[
					1:[[name:"water" , value:"dry", descriptionText:"Water Alarm Notification, Status Dry."]],
					2:[[name:"water" , value:"dry", descriptionText:"Water Alarm Notification, Status Dry."]],
					5:[[name:"filterStatus " , value:"normal", descriptionText:"Water filter good."]],				
				], 
				5001:[[name:"water" , value:"wet", descriptionText:"Water leak detected (location provided)."]], 
				5002:[[name:"water" , value:"wet", descriptionText:"Water leak detected."]],
				5005:[[name:"filterStatus " , value:"replace", descriptionText:"Replace water filter (End-of-Life)."]],				
			
			// Access Control (Locks)
				6001:[[name:"lock" , value:"locked", descriptionText:"Manual lock operation"]], 
                6002:[[name:"lock" , value:"unlocked", descriptionText:"Manual unlock operation"]], 
				6003:[[name:"lock" , value:"locked", descriptionText:"RF lock operation"]], 
				6004:[[name:"lock" , value:"unlocked", descriptionText:"RF unlock operation"]], 
				6005:[[name:"lock" , value:"locked", descriptionText:"Keypad lock operation"]], 
				6006:[[name:"lock" , value:"unlocked", descriptionText:"Keypad unlock operation"]], 
				6009:[[name:"lock" , value:"locked", descriptionText:"Auto lock locked operation"]], 
				6010:[[name:"lock" , value:"unknown", descriptionText:"Auto lock not fully locked operation"]],
				6011:[[name:"lock" , value:"unknown", descriptionText:"Lock jammed"]],
				6012:[[name:"lockStatus" , value:"All codes deleted", descriptionText:"All user codes deleted."]],
				6013:[[name:"lockStatus" , value:"Code deleted", descriptionText:"Single user code deleted."]],
				6014:[[name:"lockStatus" , value:"Code added", descriptionText:"New user code added."]],
				6015:[[name:"lockStatus" , value:"Duplicate code", descriptionText:"User code not added. Duplicate."]],
				6016:[[name:"windowDoorStatus" , value:"open", descriptionText:"Window/door is open"]],
				6017:[[name:"windoDoorStatus" , value:"closed", descriptionText:"Window/door is closed"]],
				6018:[[name:"handleStatus" , value:"open", descriptionText:"Window/door handle is open"]],
				6019:[[name:"handleStatus" , value:"closed", descriptionText:"Window/door handle is closed"]],
				6022:[[name:"contact" , value:"open", descriptionText:"Door/Window open"]], 	
				6023:[[name:"contact" , value:"closed", descriptionText:"Door/Window closed"]], 
				// 254:[[name:"lock" , value:"unknown", descriptionText:"Lock in unknown state"]],
			
			// Home Security
				7000:[	 // These events "clear" a sensor.	
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
				7001:[[name:"contact" , value:"open", descriptionText:"Contact sensor, open (location provided)"]], 	
				7002:[[name:"contact" , value:"open", descriptionText:"Contact sensor, open"]], 					
				7003:[[name:"tamper" , value:"detected", descriptionText:"Tampering 7, device cover removed"]], 
				7004:[[name:"tamper" , value:"detected", descriptionText:"Tampering, invalid code."]], 
				7005:[[name:"shock" , value:"detected", descriptionText:"Glass Breakage Detected (location provided)"]], 
				7006:[[name:"shock" , value:"detected", descriptionText:"Glass Breakage Detected"]], 				
				7007:[[name:"motion" , value:"active", descriptionText:"Motion detected (location provided)."]],
				7008:[[name:"motion" , value:"active", descriptionText:"Motion detected."]],
				7009:[[name:"tamper" , value:"detected", descriptionText:"Tampering, device moved"]],
			
			// Power Management
				8000:[ // These events "clear" a sensor
					5:[[name:"powerSource" , value:"unknown", descriptionText:"Voltage drop/drift cleared"]],
					],
				8001:[[name:"powerSource" , value:"unknown", descriptionText:"Power applied"]],
				8010:[[name:"powerSource" , value:"battery", descriptionText:"Replace battery soon"]],
				8011:[[name:"powerSource" , value:"battery", descriptionText:"Replace battery now"]],
			
			// System
				9000:[ // These events "clear" a sensor
					4:[[name:"softwareFailure" , value:"cleared", descriptionText:"System Software Report - Startup Cleared"]],
					5:[[name:"heartbeat" , value:currentDate, descriptionText:"Last Heartbeat"]],
					6:[[name:"tamper" , value:"clear", descriptionText:"Tampering, device cover replaced"]], 
					],
				9001:[[name:"hardwareFailure" , value:"unknown", descriptionText:"System Hardware Failure"]],
				9002:[[name:"softwareFailure" , value:"unknown", descriptionText:"System Software Failure"]],
				9003:[[name:"hardwareFailure" , value:(cmd.notificationStatus), descriptionText:"System Hardware Failure Report - Proprietary Code"]],
				9004:[[name:"softwareFailure" , value:(cmd.notificationStatus), descriptionText:"System Software Failure Report - Proprietary Code"]],
				9005:[[name:"heartbeat" , value:currentDate, descriptionText:"Last Heartbeat"]],
				9006:[[name:"tamper" , value:"detected", descriptionText:"Tampering, device cover removed"]], 		
            
			//  Gas Alarm
				10000:[	 // These events "clear" a sensor.	
					1:[[name:"naturalGas" , value:"clear", descriptionText:"Combustible gas (cleared) (location provided)"]], 	
					2:[[name:"naturalGas" , value:"clear", descriptionText:"Combustible gas  (cleared) "]], 
					5:[[name:"naturalGas" , value:"clear", descriptionText:"Gas detector test completed."]], 	
					6:[[name:"consumableStatus" , value:"good", descriptionText:"Gas detector (good)"]],
					], 
				10001:[[name:"naturalGas" , value:"detected", descriptionText:"Combustible gas detected (location provided)"]], 	
				10002:[[name:"naturalGas" , value:"detected", descriptionText:"Combustible gas detected"]], 
				10005:[[name:"naturalGas" , value:"tested", descriptionText:"Gas detector test"]], 	
				10006:[[name:"consumableStatus" , value:"replace", descriptionText:"Gas detector, replacement required"]],
			
			// Siren
				14000:[
					1:[[name:"alarm" , value:"off", descriptionText:"Alarm Siren Off."]],
					], 
				14001:[[name:"alarm" , value:"siren", descriptionText:"Alarm Siren On."]],
			
			// Water Valve
				15000:[[name:"valve" , value:( (cmd.event > 0 ) ? "open" : "closed"), descriptionText:"Valve Operation."]], 
				15001:[[name:"valve" , value:( (cmd.event > 0 ) ? "open" : "closed"), descriptionText:"Valve Operation."]],
				15002:[[name:"valve" , value:( (cmd.event > 0 ) ? "open" : "closed"), descriptionText:"Master Valve Operation."]],				
			
			// Home Monitoring
				22000:[
					1:[[name:"presence" , value:"not present", descriptionText:"Home not occupied"]],
					2:[[name:"presence" , value:"not present", descriptionText:"Home not occupied"]],
					], 
				22001:[[name:"presence" , value:"present", descriptionText:"Home occupied (location provided)"]],  
				22002:[[name:"presence" , value:"present", descriptionText:"Home occupied"]],
			
		].get((int) cmd.notificationType * 1000 + (int) cmd.event)

		if ((int) cmd.event == 0){
			if ((int) cmd.eventParametersLength == 1) { // This is for clearing events.
				return notifications?.get( (int) cmd.eventParameter[0] )
			} else if ((int) cmd.eventParametersLength > 1){
				log.error "In function getZWaveNotificationEvent(), received NotificationReport with eventParametersLength of unexpected size. Returning null. NotificationReport is ${cmd}"
				return null
			} else {
				return null
			}
		} else {	
			return notifications
		}
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