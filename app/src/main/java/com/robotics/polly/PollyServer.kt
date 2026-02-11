package com.robotics.polly

import android.util.Log
import fi.iki.elonen.NanoHTTPD

class PollyServer(port: Int) : NanoHTTPD(port) {
    
    var sensorData: Map<String, Any> = emptyMap()
    var onMotorCommand: ((String, Int) -> Unit)? = null
    var usbConnected: Boolean = false
    
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        
        return when {
            uri == "/" -> newFixedLengthResponse(getHomePage())
            uri == "/sensors" -> newFixedLengthResponse(Response.Status.OK, "application/json", getSensorJSON())
            uri == "/status" -> newFixedLengthResponse("OK")
            uri.startsWith("/control") -> handleMotorControl(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }
    
    private fun handleMotorControl(session: IHTTPSession): Response {
        if (!usbConnected) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                """{"error":"Arduino not connected"}"""
            )
        }
        
        val params = mutableMapOf<String, String>()
        
        // For GET requests, parse query parameters directly
        session.parameters.forEach { (key, values) ->
            if (values.isNotEmpty()) params[key] = values[0]
        }
        
        // Log for debugging
        Log.d("PollyServer", "Motor control request: params=$params, uri=${session.uri}, queryString=${session.queryParameterString}")
        
        val action = params["action"] ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            "application/json",
            """{"error":"Missing 'action' parameter", "debug":"params=$params, uri=${session.uri}"}"""
        )
        
        val speed = params["speed"]?.toIntOrNull() ?: 128
        
        onMotorCommand?.invoke(action, speed)
        
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            """{"status":"ok","action":"$action","speed":$speed}"""
        )
    }
    
    private fun getHomePage(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Polly Robot Control</title>
                <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body { 
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Arial, sans-serif;
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        min-height: 100vh;
                        padding: 20px;
                        color: #333;
                    }
                    .container { max-width: 800px; margin: 0 auto; }
                    h1 { 
                        color: white; 
                        text-align: center; 
                        margin-bottom: 20px;
                        font-size: 2em;
                        text-shadow: 0 2px 4px rgba(0,0,0,0.2);
                    }
                    .status {
                        background: rgba(255,255,255,0.95);
                        padding: 10px 15px;
                        border-radius: 12px;
                        margin-bottom: 15px;
                        text-align: center;
                        font-weight: 600;
                        box-shadow: 0 4px 6px rgba(0,0,0,0.1);
                    }
                    .status.connected { background: #4CAF50; color: white; }
                    .status.disconnected { background: #f44336; color: white; }
                    .panel { 
                        background: rgba(255,255,255,0.95);
                        padding: 20px;
                        margin: 15px 0;
                        border-radius: 16px;
                        box-shadow: 0 8px 16px rgba(0,0,0,0.15);
                    }
                    h2 { 
                        font-size: 1.3em;
                        margin-bottom: 15px;
                        color: #555;
                    }
                    .controls { 
                        display: grid;
                        grid-template-columns: repeat(3, 1fr);
                        gap: 12px;
                        max-width: 320px;
                        margin: 0 auto;
                    }
                    button { 
                        padding: 20px;
                        font-size: 28px;
                        border: none;
                        border-radius: 12px;
                        cursor: pointer;
                        background: linear-gradient(135deg, #667eea, #764ba2);
                        color: white;
                        transition: all 0.2s;
                        box-shadow: 0 4px 8px rgba(0,0,0,0.2);
                        user-select: none;
                        -webkit-tap-highlight-color: transparent;
                    }
                    button:active { 
                        transform: scale(0.95);
                        box-shadow: 0 2px 4px rgba(0,0,0,0.2);
                    }
                    .stop { 
                        background: linear-gradient(135deg, #f44336, #c62828);
                        grid-column: 2;
                    }
                    .speed-control {
                        margin: 20px 0;
                    }
                    .speed-label {
                        display: flex;
                        justify-content: space-between;
                        margin-bottom: 8px;
                        font-weight: 600;
                        color: #555;
                    }
                    input[type="range"] {
                        width: 100%;
                        height: 8px;
                        border-radius: 4px;
                        background: #ddd;
                        outline: none;
                        -webkit-appearance: none;
                    }
                    input[type="range"]::-webkit-slider-thumb {
                        -webkit-appearance: none;
                        width: 24px;
                        height: 24px;
                        border-radius: 50%;
                        background: linear-gradient(135deg, #667eea, #764ba2);
                        cursor: pointer;
                        box-shadow: 0 2px 4px rgba(0,0,0,0.2);
                    }
                    input[type="range"]::-moz-range-thumb {
                        width: 24px;
                        height: 24px;
                        border-radius: 50%;
                        background: linear-gradient(135deg, #667eea, #764ba2);
                        cursor: pointer;
                        border: none;
                        box-shadow: 0 2px 4px rgba(0,0,0,0.2);
                    }
                    #data {
                        background: #f8f9fa;
                        padding: 15px;
                        border-radius: 8px;
                        font-family: 'Courier New', monospace;
                        font-size: 12px;
                        overflow-x: auto;
                        max-height: 300px;
                        overflow-y: auto;
                    }
                    .sensor-grid {
                        display: grid;
                        grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
                        gap: 10px;
                        margin-top: 10px;
                    }
                    .sensor-item {
                        background: #f8f9fa;
                        padding: 10px;
                        border-radius: 8px;
                        text-align: center;
                    }
                    .sensor-value {
                        font-size: 1.5em;
                        font-weight: bold;
                        color: #667eea;
                    }
                    .sensor-label {
                        font-size: 0.9em;
                        color: #777;
                        margin-top: 4px;
                    }
                </style>
                <script>
                    let currentSpeed = 128;
                    let isConnected = false;
                    
                    function updateStatus(connected) {
                        const status = document.getElementById('status');
                        isConnected = connected;
                        status.className = 'status ' + (connected ? 'connected' : 'disconnected');
                        status.innerText = connected ? '🟢 Connected' : '🔴 Disconnected';
                    }
                    
                    function updateSensors() {
                        fetch('/sensors')
                            .then(r => r.json())
                            .then(data => {
                                updateStatus(true);
                                document.getElementById('data').innerText = JSON.stringify(data, null, 2);
                                // Future: Could parse and display in visual sensor grid
                            })
                            .catch(() => updateStatus(false));
                    }
                    
                    function sendCommand(action) {
                        const speed = action === 'stop' ? 0 : currentSpeed;
                        fetch('/control?action=' + action + '&speed=' + speed)
                            .then(r => r.json())
                            .then(data => {
                                console.log('Command sent:', data);
                                updateStatus(true);
                            })
                            .catch(err => {
                                console.error('Error:', err);
                                updateStatus(false);
                            });
                    }
                    
                    function updateSpeed(value) {
                        currentSpeed = parseInt(value);
                        document.getElementById('speedValue').innerText = value;
                    }
                    
                    // Keyboard controls - track pressed keys
                    let pressedKeys = new Set();
                    
                    document.addEventListener('keydown', (e) => {
                        // Prevent default for arrow keys (stop page scroll)
                        if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', ' '].includes(e.key)) {
                            e.preventDefault();
                        }
                        
                        // Ignore repeat events
                        if (e.repeat) return;
                        
                        pressedKeys.add(e.key);
                        
                        switch(e.key) {
                            case 'ArrowUp': case 'w': case 'W': sendCommand('forward'); break;
                            case 'ArrowDown': case 's': case 'S': sendCommand('backward'); break;
                            case 'ArrowLeft': case 'a': case 'A': sendCommand('left'); break;
                            case 'ArrowRight': case 'd': case 'D': sendCommand('right'); break;
                            case ' ': sendCommand('stop'); break;
                        }
                    });
                    
                    document.addEventListener('keyup', (e) => {
                        pressedKeys.delete(e.key);
                        
                        // Stop when no movement keys are pressed
                        const movementKeys = ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'w', 'W', 's', 'S', 'a', 'A', 'd', 'D'];
                        const hasMovementKey = movementKeys.some(key => pressedKeys.has(key));
                        
                        if (!hasMovementKey) {
                            sendCommand('stop');
                        }
                    });
                    
                    setInterval(updateSensors, 200);
                    updateSensors();
                </script>
            </head>
            <body>
                <div class="container">
                    <h1>🤖 Polly Robot Control</h1>
                    <div id="status" class="status">Connecting...</div>
                    
                    <div class="panel">
                        <h2>🎮 Motor Controls</h2>
                        <div class="speed-control">
                            <div class="speed-label">
                                <span>Speed:</span>
                                <span id="speedValue">128</span>
                            </div>
                            <input type="range" min="50" max="255" value="128" 
                                   oninput="updateSpeed(this.value)">
                        </div>
                        <div class="controls">
                            <div></div>
                            <button onclick="sendCommand('forward')" ontouchstart="sendCommand('forward')">⬆️</button>
                            <div></div>
                            <button onclick="sendCommand('left')" ontouchstart="sendCommand('left')">⬅️</button>
                            <button class="stop" onclick="sendCommand('stop')" ontouchstart="sendCommand('stop')">⏹️</button>
                            <button onclick="sendCommand('right')" ontouchstart="sendCommand('right')">➡️</button>
                            <div></div>
                            <button onclick="sendCommand('backward')" ontouchstart="sendCommand('backward')">⬇️</button>
                            <div></div>
                        </div>
                        <div style="text-align: center; margin-top: 15px; color: #777; font-size: 0.9em;">
                            Use arrow keys or WASD to control
                        </div>
                    </div>
                    
                    <div class="panel">
                        <h2>📐 Sensor Data</h2>
                        <pre id="data">Loading...</pre>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
    
    private fun getSensorJSON(): String {
        return buildString {
            append("{")
            sensorData.entries.forEachIndexed { index, (key, value) ->
                append("\"$key\":")
                when (value) {
                    is String -> append("\"$value\"")
                    is Number -> append(value)
                    else -> append("\"$value\"")
                }
                if (index < sensorData.size - 1) append(",")
            }
            append("}")
        }
    }
}
