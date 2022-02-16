library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Handle Binary and MultiLevel Sensors",
        name: "sensorTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "zwaveTools.endpointTools",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/sensorTools.groovy"
)

//////////////////////////////////////////////////////////////////////
//////        Handle   Binary Sensor     ///////
//////////////////////////////////////////////////////////////////////
void sensorTools_refresh(Integer ep = null ) {
	binarySensorRefresh(ep)
	multilevelSensorRefresh(ep)

}
void	binarySensorRefresh(Integer ep = null ) {
	log.warn "Device ${device}: binarySensorRefresh function is not implemented."
}

void multilevelSensorRefresh(Integer ep =  null ) {
	log.warn "Device ${device}: multilevelSensorRefresh function is not implemented."
}

Map getFormattedZWaveSensorBinaryEvent(hubitat.zwave.commands.sensorbinaryv2.SensorBinaryReport cmd)
{
	Map returnEvent = [ 	
			// Smoke
				2000:[name:"smoke" , value:"clear", descriptionText:"Smoke detector status Idle."],
				2255:[name:"smoke" , value:"detected", descriptionText:"Smoke detected."], 

			// CO
				3000:[name:"carbonMonoxide" , value:"clear", descriptionText:"Carbon Monoxide status."],
				3255:[name:"carbonMonoxide" , value:"detected", descriptionText:"Carbon Monoxide detected."],
				
			// CO2
				4000:[name:"carbonDioxideDetected" , value:"clear", descriptionText:"Carbon Dioxide status."],	
				4255:[name:"carbonDioxideDetected" , value:"detected", descriptionText:"Carbon Dioxide detected."],
				
			// Water
				6000:[name:"water" , value:"dry", descriptionText:"Water Alarm Notification, Status Dry."],
				6255:[name:"water" , value:"wet", descriptionText:"Water leak detected."],
			
			// Tamper
				8000:[name:"tamper" , value:"clear", descriptionText:"Tamper state cleared."],
				8255:[name:"tamper" , value:"detected", descriptionText:"Tampering, device cover removed"], 
			
			// Door/Window
				10000:[name:"contact" , value:"closed", descriptionText:"Contact sensor, closed"], 					
				10255:[name:"contact" , value:"open", descriptionText:"Contact sensor, open"], 					
			
			// Motion
				12000:[name:"motion" , value:"inactive", descriptionText:"Motion Inactive."],
				12255:[name:"motion" , value:"active", descriptionText:"Motion detected."],
	
		].get(((int) cmd.sensorType * 1000 + (int) cmd.sensorValue))
		
		return returnEvent
}

void zwaveEvent(hubitat.zwave.commands.sensorbinaryv2.SensorBinaryReport cmd, Integer ep = null )
{
	Map thisEvent = getFormattedZWaveSensorBinaryEvent(cmd)
	sendEventToEndpoints(event:thisEvent, ep:ep)
}

//////////////////////////////////////////////////////////////////////
//////        Handle  Multilevel Sensor       ///////
//////////////////////////////////////////////////////////////////////
Map getFormattedZWaveSensorMultilevelReportEvent(hubitat.zwave.commands.sensormultilevelv11.SensorMultilevelReport cmd)
{
	Map tempReport = [
		1: [name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Air temperature"], 
		23:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Water temperature"], 
		24:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Soil temperature"], 
		34:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Target temperature"], 
		62:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Boiler Water temperature"],
		63:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Domestic Hot Water temperature"], 
		64:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Outside temperature"], 
		65:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Exhaust temperature"],
		72:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Return Air temperature"],		
		73:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Supply Air temperature"],		
		74:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Condenser Coil temperature"],		
		75:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Evaporator Coil temperature"],		
		76:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Liquid Line temperature"],
		77:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Discharge Line temperature"],
		80:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Defrost temperature"],	
	].get((int) cmd.sensorType )

	if (tempReport) {
		tempReport.value = convertTemperatureIfNeeded(cmd.scaledSensorValue, ((((int) cmd.scale ) == 0) ? "C" : "F"), 2)
		return tempReport + [deviceType:"ZWV", zwaveOriginalMessage:cmd.format()]
	}
	
	Map otherSensorReport = [
		3000:[name: "illuminance", value: cmd.scaledSensorValue, unit: "%"], // Illuminance
		3001:[name: "illuminance", value: cmd.scaledSensorValue, unit: "lx"],
		4000:[name: "power", value: cmd.scaledSensorValue, unit: "W"],
		4001:[name: "power", value: cmd.scaledSensorValue, unit: "BTU/h"],
		5000:[name: "humidity", value: cmd.scaledSensorValue, unit: "%"],
		5001:[name: "humidity", value: cmd.scaledSensorValue, unit: "g/m3"],
		8000:[name: "pressure", value: (cmd.scaledSensorValue * ((cmd.scale == 0) ? 1000 : 3386.38867)), unit:"Pa", descriptionText:"Atmospheric Pressure"],
		9000:[name: "pressure", value: (cmd.scaledSensorValue * ((cmd.scale == 0) ? 1000 : 3386.38867)), unit:"Pa", descriptionText:"Barometric Pressure"],
		15000:[name: "voltage", value: cmd.scaledSensorValue, unit: "V"],
		15001:[name: "voltage", value: cmd.scaledSensorValue, unit: "mV"],
		16000:[name: "amperage", value: cmd.scaledSensorValue, unit: "A"],
		16001:[name: "amperage", value: cmd.scaledSensorValue, unit: "mA"],
		17000:[name: "carbonDioxide ", value: cmd.scaledSensorValue, unit: "ppm"],
		27000:[name: "ultravioletIndex", value: cmd.scaledSensorValue, unit: "UV Index"],
		40000:[name: "carbonMonoxide ", value: cmd.scaledSensorValue, unit: "ppm"],
		56000:[name: "rate", value: cmd.scaledSensorValue, unit: "LPH"], // Water flow	
		58000:[name: "rssi", value: cmd.scaledSensorValue, unit: "%"],
		58001:[name: "rssi", value: cmd.scaledSensorValue, unit: "dBm"],
		67000:[name: "pH", value: cmd.scaledSensorValue, unit: "pH"],
	].get(((int) cmd.sensorType * 1000 + (int) cmd.scale))	
	
	return otherSensorReport
}

void zwaveEvent(hubitat.zwave.commands.sensormultilevelv11.SensorMultilevelReport cmd, Integer ep = null )
{
	Map thisEvent = getFormattedZWaveSensorMultilevelReportEvent(cmd)
	sendEventToEndpoints(event:thisEvent, ep:ep)
}
