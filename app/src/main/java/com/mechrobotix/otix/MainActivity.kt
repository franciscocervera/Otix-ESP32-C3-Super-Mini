package com.mechrobotix.otix

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var motorController: BleMotorController
    private lateinit var sensorManager: SensorManager
    private var orientationSensor: Sensor? = null
    private val handler = Handler(Looper.getMainLooper())
    private val movementButtons = linkedMapOf<MaterialButton, MovementCommand>()
    private lateinit var txtConnectionState: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtMovement: TextView
    private lateinit var txtTimer: TextView
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnDisconnect: MaterialButton
    private lateinit var btnVoice: MaterialButton
    private lateinit var btnDemoStart: MaterialButton
    private lateinit var btnDemoStop: MaterialButton
    private lateinit var btnTimerStart: MaterialButton
    private lateinit var btnTimerStop: MaterialButton
    private lateinit var btnTimerReset: MaterialButton
    private lateinit var btnSlow: MaterialButton
    private lateinit var btnMedium: MaterialButton
    private lateinit var btnFast: MaterialButton
    private lateinit var btnHelp: ExtendedFloatingActionButton
    private lateinit var speedGroup: MaterialButtonToggleGroup
    private lateinit var switchGyroscope: SwitchMaterial
    private lateinit var pickerHours: NumberPicker
    private lateinit var pickerMinutes: NumberPicker
    private lateinit var pickerSeconds: NumberPicker
    private var activeMovement: MovementCommand = MovementCommand.STOP
    private var selectedSpeed = SpeedLevel.MEDIUM
    private var demoRunning = false
    private var demoRunnable: Runnable? = null
    private var timer: CountDownTimer? = null
    private var remainingTimerMs = 0L
    private var gyroscopeEnabled = false
    private var updatingGyroscopeSwitch = false
    private var basePitch = 0f
    private var baseRoll = 0f
    private var gyroscopeCalibrated = false
    private var lastGyroscopeCommand = MovementCommand.STOP
    private var lastGyroscopeSendAt = 0L
    private var pendingScanAfterPermission = false
    private var connectionState = BleMotorController.ConnectionState.DISCONNECTED
    private var timerRunning = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceListening = false
    private var voiceRestartPending = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        if (granted && pendingScanAfterPermission) {
            pendingScanAfterPermission = false
            ensureBluetoothAndScan()
        } else if (!granted) {
            pendingScanAfterPermission = false
            updateStatus("Se requieren permisos Bluetooth para conectar", true)
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (motorController.isBluetoothEnabled()) {
            motorController.startScan()
        } else {
            updateStatus("Bluetooth continúa desactivado", true)
        }
    }

    private val voicePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startContinuousVoiceRecognition()
        else updateStatus("Se requiere permiso de micrófono para usar comandos de voz", true)
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (motorController.isReady()) motorController.sendHeartbeat()
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bindViews()
        configureTimerPickers()
        sensorManager = getSystemService(SensorManager::class.java)
            ?: throw IllegalStateException("SensorManager no disponible")
        orientationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        motorController = BleMotorController(this, ::onConnectionStateChanged, ::onTelemetry)
        bindControls()
        selectSpeed(SpeedLevel.MEDIUM, false)
        setMovement(MovementCommand.STOP)
        updateConnectionUi(BleMotorController.ConnectionState.DISCONNECTED)
        updateStatus("Pulsa Conectar para buscar el robot OTIX")
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(heartbeatRunnable)
        handler.post(heartbeatRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(heartbeatRunnable)
        stopContinuousVoiceRecognition(false)
        stopDemo()
        stopTimer(true)
        disableGyroscope(true)
        sendMovement(MovementCommand.STOP, false)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        timer?.cancel()
        sensorManager.unregisterListener(this)
        speechRecognizer?.destroy()
        speechRecognizer = null
        motorController.close()
        super.onDestroy()
    }

    private fun bindViews() {
        txtConnectionState = findViewById(R.id.txtConnectionState)
        txtStatus = findViewById(R.id.txtStatus)
        txtMovement = findViewById(R.id.txtMovement)
        txtTimer = findViewById(R.id.txtTimer)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnVoice = findViewById(R.id.btnVoice)
        btnDemoStart = findViewById(R.id.btnDemoStart)
        btnDemoStop = findViewById(R.id.btnDemoStop)
        btnTimerStart = findViewById(R.id.btnTimerStart)
        btnTimerStop = findViewById(R.id.btnTimerStop)
        btnTimerReset = findViewById(R.id.btnTimerReset)
        btnSlow = findViewById(R.id.btnSlow)
        btnMedium = findViewById(R.id.btnMedium)
        btnFast = findViewById(R.id.btnFast)
        btnHelp = findViewById(R.id.btnHelp)
        speedGroup = findViewById(R.id.speedGroup)
        switchGyroscope = findViewById(R.id.switchGyroscope)
        pickerHours = findViewById(R.id.pickerHours)
        pickerMinutes = findViewById(R.id.pickerMinutes)
        pickerSeconds = findViewById(R.id.pickerSeconds)
        movementButtons[findViewById(R.id.btnForwardLeft)] = MovementCommand.FORWARD_LEFT
        movementButtons[findViewById(R.id.btnForward)] = MovementCommand.FORWARD
        movementButtons[findViewById(R.id.btnForwardRight)] = MovementCommand.FORWARD_RIGHT
        movementButtons[findViewById(R.id.btnLeft)] = MovementCommand.LEFT
        movementButtons[findViewById(R.id.btnStop)] = MovementCommand.STOP
        movementButtons[findViewById(R.id.btnRight)] = MovementCommand.RIGHT
        movementButtons[findViewById(R.id.btnBackwardLeft)] = MovementCommand.BACKWARD_LEFT
        movementButtons[findViewById(R.id.btnBackward)] = MovementCommand.BACKWARD
        movementButtons[findViewById(R.id.btnBackwardRight)] = MovementCommand.BACKWARD_RIGHT
    }

    private fun bindControls() {
        btnConnect.setOnClickListener { requestConnection() }
        btnDisconnect.setOnClickListener {
            stopAllActions()
            motorController.disconnect()
        }
        btnVoice.setOnClickListener { toggleVoiceRecognition() }
        btnVoice.setOnLongClickListener {
            showHelpDialog()
            true
        }
        btnDemoStart.setOnClickListener { startDemo() }
        btnDemoStop.setOnClickListener { stopDemo() }
        btnHelp.setOnClickListener { showHelpDialog() }
        btnTimerStart.setOnClickListener { startTimer() }
        btnTimerStop.setOnClickListener { stopTimer(true) }
        btnTimerReset.setOnClickListener { resetTimer() }
        movementButtons.forEach { (button, command) -> bindMovementButton(button, command) }
        speedGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnSlow -> selectSpeed(SpeedLevel.SLOW, true)
                R.id.btnMedium -> selectSpeed(SpeedLevel.MEDIUM, true)
                R.id.btnFast -> selectSpeed(SpeedLevel.FAST, true)
            }
        }
        switchGyroscope.setOnCheckedChangeListener { _, checked ->
            if (updatingGyroscopeSwitch) return@setOnCheckedChangeListener
            if (checked) enableGyroscope() else disableGyroscope(true)
        }
    }

    private fun bindMovementButton(button: MaterialButton, command: MovementCommand) {
        if (command == MovementCommand.STOP) {
            button.setOnClickListener {
                stopDemo()
                disableGyroscope(false)
                sendMovement(MovementCommand.STOP, true)
            }
            return
        }
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stopDemo()
                    disableGyroscope(false)
                    sendMovement(command, true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> sendMovement(MovementCommand.STOP, false)
            }
            true
        }
    }

    private fun requestConnection() {
        if (!motorController.isBluetoothAvailable()) {
            updateStatus("Este teléfono no dispone de Bluetooth", true)
            return
        }
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            pendingScanAfterPermission = true
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        ensureBluetoothAndScan()
    }

    private fun ensureBluetoothAndScan() {
        if (!motorController.isBluetoothEnabled()) {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (intent.resolveActivity(packageManager) != null) {
                bluetoothEnableLauncher.launch(intent)
            } else {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            return
        }
        motorController.startScan()
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun sendMovement(command: MovementCommand, reportFailure: Boolean): Boolean {
        if (!motorController.isReady()) {
            if (reportFailure) updateStatus("Conecta el robot antes de enviar movimientos", true)
            setMovement(MovementCommand.STOP)
            return false
        }
        val sent = motorController.send(command)
        if (sent) {
            setMovement(command)
            updateStatus("Movimiento: ${command.displayName}")
        } else if (reportFailure) {
            updateStatus("No se pudo enviar el movimiento", true)
        }
        return sent
    }

    private fun setMovement(command: MovementCommand) {
        activeMovement = command
        txtMovement.text = command.displayName
        movementButtons.forEach { (button, mappedCommand) ->
            val selected = mappedCommand == command
            val tint = when {
                mappedCommand == MovementCommand.STOP && selected -> R.color.danger
                mappedCommand == MovementCommand.STOP -> R.color.danger_dark
                selected -> R.color.accent
                else -> R.color.accent_dark
            }
            val contentColor = ContextCompat.getColor(
                this,
                if (selected && mappedCommand != MovementCommand.STOP) R.color.bg_dark else R.color.text_primary
            )
            button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, tint))
            button.setTextColor(contentColor)
            button.iconTint = ColorStateList.valueOf(contentColor)
        }
    }

    private fun selectSpeed(speed: SpeedLevel, send: Boolean) {
        selectedSpeed = speed
        val targetId = when (speed) {
            SpeedLevel.SLOW -> R.id.btnSlow
            SpeedLevel.MEDIUM -> R.id.btnMedium
            SpeedLevel.FAST -> R.id.btnFast
        }
        if (speedGroup.checkedButtonId != targetId) speedGroup.check(targetId)
        updateSpeedSelectionUi(targetId)
        if (send) {
            if (motorController.sendSpeed(speed)) {
                updateStatus("Velocidad: ${speed.displayName}")
            } else {
                updateStatus("Conecta el robot para cambiar la velocidad", true)
            }
        }
    }

    private fun toggleVoiceRecognition() {
        if (voiceListening) {
            stopContinuousVoiceRecognition(true)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startContinuousVoiceRecognition()
    }

    private fun startContinuousVoiceRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateStatus("No hay un servicio de reconocimiento de voz disponible", true)
            return
        }
        if (speechRecognizer == null) configureSpeechRecognizer()
        voiceListening = true
        updateVoiceUi()
        updateStatus("Comandos de voz activos. Di una instrucción")
        startVoiceListeningCycle()
    }

    private fun configureSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit

                override fun onResults(results: Bundle?) {
                    val phrase = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (phrase.isNotBlank()) handleVoiceCommand(phrase)
                    scheduleVoiceRestart()
                }

                override fun onError(error: Int) {
                    if (!voiceListening) return
                    when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            stopContinuousVoiceRecognition(false)
                            updateStatus("No hay permiso para acceder al micrófono", true)
                        }
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleVoiceRestart(500L)
                        SpeechRecognizer.ERROR_CLIENT -> scheduleVoiceRestart(500L)
                        else -> scheduleVoiceRestart()
                    }
                }
            })
        }
    }

    private fun startVoiceListeningCycle() {
        if (!voiceListening || voiceRestartPending) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun scheduleVoiceRestart(delayMs: Long = VOICE_RESTART_DELAY_MS) {
        if (!voiceListening || voiceRestartPending) return
        voiceRestartPending = true
        handler.postDelayed({
            voiceRestartPending = false
            startVoiceListeningCycle()
        }, delayMs)
    }

    private fun stopContinuousVoiceRecognition(showStatus: Boolean) {
        voiceListening = false
        voiceRestartPending = false
        speechRecognizer?.cancel()
        updateVoiceUi()
        if (showStatus) updateStatus("Comandos de voz desactivados")
    }

    private fun updateVoiceUi() {
        btnVoice.text = if (voiceListening) "Desactivar voz" else "Comando de voz"
        val color = if (voiceListening) R.color.danger else R.color.blue_action
        btnVoice.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))
    }

    private fun handleVoiceCommand(rawPhrase: String) {
        val phrase = normalize(rawPhrase)
        updateStatus("Voz: $rawPhrase")
        when {
            containsAny(phrase, "desactivar voz", "apagar voz", "dejar de escuchar", "detener escucha") -> {
                stopContinuousVoiceRecognition(true)
                return
            }
            phrase.contains("apagar demo") || phrase.contains("detener demo") -> {
                stopDemo()
                return
            }
            phrase.contains("demo") || phrase.contains("demostracion") -> {
                startDemo()
                return
            }
        }
        val speedChanged = when {
            containsAny(phrase, "rapido", "maxima", "maximo") -> {
                selectSpeed(SpeedLevel.FAST, true)
                true
            }
            containsAny(phrase, "medio", "normal") -> {
                selectSpeed(SpeedLevel.MEDIUM, true)
                true
            }
            containsAny(phrase, "lento", "despacio") -> {
                selectSpeed(SpeedLevel.SLOW, true)
                true
            }
            else -> false
        }
        val stop = containsAny(phrase, "deten", "detener", "para", "parar", "alto", "stop", "quieto")
        if (stop) {
            stopDemo()
            disableGyroscope(false)
            sendMovement(MovementCommand.STOP, true)
            return
        }
        val forward = containsAny(phrase, "adelante", "avanza", "avanzar", "frente")
        val backward = containsAny(phrase, "atras", "retrocede", "retroceder", "reversa")
        val left = containsAny(phrase, "izquierda", "izquierdo")
        val right = containsAny(phrase, "derecha", "derecho")
        val command = when {
            forward && left -> MovementCommand.FORWARD_LEFT
            forward && right -> MovementCommand.FORWARD_RIGHT
            backward && left -> MovementCommand.BACKWARD_LEFT
            backward && right -> MovementCommand.BACKWARD_RIGHT
            forward -> MovementCommand.FORWARD
            backward -> MovementCommand.BACKWARD
            left -> MovementCommand.LEFT
            right -> MovementCommand.RIGHT
            else -> null
        }
        if (command == null) {
            if (!speedChanged) updateStatus("No se reconoció un comando compatible", true)
            return
        }
        stopDemo()
        disableGyroscope(false)
        sendMovement(command, true)
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
    }

    private fun containsAny(value: String, vararg options: String): Boolean = options.any { value.contains(it) }

    private fun enableGyroscope() {
        if (!motorController.isReady()) {
            setGyroscopeSwitch(false)
            updateStatus("Conecta el robot antes de activar el giroscopio", true)
            return
        }
        val sensor = orientationSensor
        if (sensor == null) {
            setGyroscopeSwitch(false)
            updateStatus("Este teléfono no tiene un sensor de orientación compatible", true)
            return
        }
        stopDemo()
        gyroscopeCalibrated = false
        lastGyroscopeCommand = MovementCommand.STOP
        gyroscopeEnabled = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        if (!gyroscopeEnabled) {
            setGyroscopeSwitch(false)
            updateStatus("No se pudo activar el sensor de orientación", true)
            return
        }
        updateStatus("Giroscopio activo, mantén el teléfono en posición neutra")
    }

    private fun disableGyroscope(sendStop: Boolean) {
        if (gyroscopeEnabled) sensorManager.unregisterListener(this)
        gyroscopeEnabled = false
        gyroscopeCalibrated = false
        lastGyroscopeCommand = MovementCommand.STOP
        setGyroscopeSwitch(false)
        if (sendStop && motorController.isReady()) sendMovement(MovementCommand.STOP, false)
    }

    private fun setGyroscopeSwitch(value: Boolean) {
        if (switchGyroscope.isChecked == value) return
        updatingGyroscopeSwitch = true
        switchGyroscope.isChecked = value
        updatingGyroscopeSwitch = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!gyroscopeEnabled) return
        val rotation = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        SensorManager.getOrientation(rotation, orientation)
        val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
        val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
        if (!gyroscopeCalibrated) {
            basePitch = pitch
            baseRoll = roll
            gyroscopeCalibrated = true
            updateStatus("Giroscopio calibrado")
            return
        }
        val pitchDelta = angleDelta(pitch, basePitch)
        val rollDelta = angleDelta(roll, baseRoll)
        val vertical = when {
            pitchDelta <= -GYRO_THRESHOLD -> 1
            pitchDelta >= GYRO_THRESHOLD -> -1
            abs(pitchDelta) <= GYRO_NEUTRAL -> 0
            else -> 0
        }
        val horizontal = when {
            rollDelta <= -GYRO_THRESHOLD -> -1
            rollDelta >= GYRO_THRESHOLD -> 1
            abs(rollDelta) <= GYRO_NEUTRAL -> 0
            else -> 0
        }
        val command = when {
            vertical < 0 && horizontal < 0 -> MovementCommand.FORWARD_LEFT
            vertical < 0 && horizontal > 0 -> MovementCommand.FORWARD_RIGHT
            vertical > 0 && horizontal < 0 -> MovementCommand.BACKWARD_LEFT
            vertical > 0 && horizontal > 0 -> MovementCommand.BACKWARD_RIGHT
            vertical < 0 -> MovementCommand.FORWARD
            vertical > 0 -> MovementCommand.BACKWARD
            horizontal < 0 -> MovementCommand.LEFT
            horizontal > 0 -> MovementCommand.RIGHT
            else -> MovementCommand.STOP
        }
        val now = System.currentTimeMillis()
        if (command != lastGyroscopeCommand && now - lastGyroscopeSendAt >= GYRO_SEND_INTERVAL_MS) {
            lastGyroscopeCommand = command
            lastGyroscopeSendAt = now
            sendMovement(command, false)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun angleDelta(value: Float, baseline: Float): Float {
        var delta = value - baseline
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        return delta
    }

    private fun startDemo() {
        if (!motorController.isReady()) {
            updateStatus("Conecta el robot antes de iniciar la demostración", true)
            return
        }
        stopDemo()
        disableGyroscope(false)
        demoRunning = true
        updateControlStates()
        motorController.sendSpeed(SpeedLevel.MEDIUM)
        runDemoStep(0)
        updateStatus("Demostración iniciada")
    }

    private fun runDemoStep(index: Int) {
        if (!demoRunning) return
        if (index >= DEMO_SEQUENCE.size) {
            stopDemo()
            updateStatus("Demostración terminada")
            return
        }
        val step = DEMO_SEQUENCE[index]
        if (!sendMovement(step.command, false)) {
            stopDemo()
            return
        }
        demoRunnable?.let { handler.removeCallbacks(it) }
        demoRunnable = Runnable { runDemoStep(index + 1) }
        handler.postDelayed(demoRunnable!!, step.durationMs)
    }

    private fun stopDemo() {
        if (!demoRunning) {
            updateControlStates()
            return
        }
        demoRunning = false
        demoRunnable?.let { handler.removeCallbacks(it) }
        demoRunnable = null
        updateControlStates()
        if (motorController.isReady()) sendMovement(MovementCommand.STOP, false)
        motorController.sendSpeed(selectedSpeed)
    }

    private fun configureTimerPickers() {
        configurePicker(pickerHours, 0, 23)
        configurePicker(pickerMinutes, 0, 59)
        configurePicker(pickerSeconds, 0, 59)
        updateTimerText(0L)
    }

    private fun configurePicker(picker: NumberPicker, min: Int, max: Int) {
        picker.minValue = min
        picker.maxValue = max
        picker.wrapSelectorWheel = true
        picker.setFormatter { value -> String.format(Locale.getDefault(), "%02d", value) }
        picker.setOnValueChangedListener { _, _, _ -> updateControlStates() }
    }

    private fun startTimer() {
        if (!motorController.isReady()) {
            updateStatus("Conecta el robot antes de iniciar el temporizador", true)
            return
        }
        val totalSeconds = pickerHours.value * 3600L + pickerMinutes.value * 60L + pickerSeconds.value
        if (totalSeconds <= 0L) {
            updateStatus("Selecciona una duración mayor que cero", true)
            return
        }
        timer?.cancel()
        timerRunning = true
        remainingTimerMs = totalSeconds * 1000L
        updateControlStates()
        updateTimerText(remainingTimerMs)
        timer = object : CountDownTimer(remainingTimerMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                remainingTimerMs = millisUntilFinished
                updateTimerText(millisUntilFinished)
            }

            override fun onFinish() {
                remainingTimerMs = 0L
                timerRunning = false
                updateTimerText(0L)
                updateControlStates()
                sendMovement(MovementCommand.STOP, false)
                updateStatus("Temporizador finalizado")
            }
        }.start()
        updateStatus("Temporizador iniciado")
    }

    private fun stopTimer(stopRobot: Boolean) {
        timer?.cancel()
        timer = null
        timerRunning = false
        updateControlStates()
        if (stopRobot && motorController.isReady()) sendMovement(MovementCommand.STOP, false)
        if (remainingTimerMs > 0L) updateStatus("Temporizador detenido")
    }

    private fun resetTimer() {
        stopTimer(true)
        remainingTimerMs = 0L
        pickerHours.value = 0
        pickerMinutes.value = 0
        pickerSeconds.value = 0
        updateTimerText(0L)
        updateControlStates()
        updateStatus("Temporizador reiniciado")
    }

    private fun updateTimerText(milliseconds: Long) {
        val totalSeconds = (milliseconds + 999L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = totalSeconds % 3600L / 60L
        val seconds = totalSeconds % 60L
        txtTimer.text = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun stopAllActions() {
        stopDemo()
        stopTimer(true)
        disableGyroscope(true)
        if (motorController.isReady()) sendMovement(MovementCommand.STOP, false)
    }

    private fun onConnectionStateChanged(state: BleMotorController.ConnectionState, message: String) {
        updateConnectionUi(state)
        updateStatus(message, state == BleMotorController.ConnectionState.ERROR)
        if (state == BleMotorController.ConnectionState.READY) {
            motorController.sendSpeed(selectedSpeed)
            motorController.send(MovementCommand.STOP)
        }
        if (state == BleMotorController.ConnectionState.DISCONNECTED || state == BleMotorController.ConnectionState.ERROR) {
            stopAllActions()
            setMovement(MovementCommand.STOP)
        }
    }

    private fun onTelemetry(message: String) {
        when {
            message.startsWith("MOVE:") -> {
                val wire = message.substringAfter("MOVE:").trim()
                MovementCommand.entries.firstOrNull { it.wire == wire }?.let(::setMovement)
            }
            message.startsWith("SPEED:") -> updateStatus("Velocidad confirmada: ${message.substringAfter("SPEED:")}")
            message == "READY" -> updateStatus("OTIX listo")
            message.startsWith("ERROR:") -> updateStatus(message.substringAfter("ERROR:"), true)
            message == "TIMEOUT" -> {
                setMovement(MovementCommand.STOP)
                updateStatus("El robot se detuvo por seguridad", true)
            }
            else -> updateStatus(message)
        }
    }

    private fun updateConnectionUi(state: BleMotorController.ConnectionState) {
        connectionState = state
        txtConnectionState.text = when (state) {
            BleMotorController.ConnectionState.DISCONNECTED -> "Desconectado"
            BleMotorController.ConnectionState.SCANNING -> "Buscando"
            BleMotorController.ConnectionState.CONNECTING -> "Conectando"
            BleMotorController.ConnectionState.DISCOVERING -> "Preparando"
            BleMotorController.ConnectionState.READY -> "Conectado"
            BleMotorController.ConnectionState.ERROR -> "Error"
        }
        val color = when (state) {
            BleMotorController.ConnectionState.READY -> R.color.success
            BleMotorController.ConnectionState.ERROR -> R.color.danger
            BleMotorController.ConnectionState.SCANNING,
            BleMotorController.ConnectionState.CONNECTING,
            BleMotorController.ConnectionState.DISCOVERING -> R.color.warning
            BleMotorController.ConnectionState.DISCONNECTED -> R.color.text_secondary
        }
        txtConnectionState.setTextColor(ContextCompat.getColor(this, color))
        updateControlStates()
    }

    private fun updateSpeedSelectionUi(selectedId: Int) {
        listOf(btnSlow, btnMedium, btnFast).forEach { button ->
            val selected = button.id == selectedId
            val background = if (selected) R.color.accent else R.color.surface_alt
            val foreground = if (selected) R.color.bg_dark else R.color.text_primary
            button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, background))
            button.setTextColor(ContextCompat.getColor(this, foreground))
            button.strokeWidth = if (selected) resources.getDimensionPixelSize(R.dimen.speed_selected_stroke) else resources.getDimensionPixelSize(R.dimen.speed_idle_stroke)
            button.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, if (selected) R.color.accent else R.color.stroke))
        }
    }

    private fun updateControlStates() {
        val ready = connectionState == BleMotorController.ConnectionState.READY
        val connectionInProgress = connectionState == BleMotorController.ConnectionState.SCANNING ||
            connectionState == BleMotorController.ConnectionState.CONNECTING ||
            connectionState == BleMotorController.ConnectionState.DISCOVERING

        btnConnect.isEnabled = !ready && !connectionInProgress
        btnDisconnect.isEnabled = ready || connectionInProgress
        btnVoice.isEnabled = ready
        if (!ready && voiceListening) stopContinuousVoiceRecognition(false)
        btnDemoStart.isEnabled = ready && !demoRunning
        btnDemoStop.isEnabled = ready && demoRunning
        switchGyroscope.isEnabled = ready
        speedGroup.isEnabled = ready
        listOf(btnSlow, btnMedium, btnFast).forEach { it.isEnabled = ready }
        movementButtons.keys.forEach { it.isEnabled = ready }

        btnTimerStart.isEnabled = ready && !timerRunning
        btnTimerStop.isEnabled = ready && timerRunning
        btnTimerReset.isEnabled = timerRunning || remainingTimerMs > 0L ||
            pickerHours.value > 0 || pickerMinutes.value > 0 || pickerSeconds.value > 0
        pickerHours.isEnabled = !timerRunning
        pickerMinutes.isEnabled = !timerRunning
        pickerSeconds.isEnabled = !timerRunning
    }

    private fun updateStatus(message: String, error: Boolean = false) {
        txtStatus.text = message
        txtStatus.setTextColor(ContextCompat.getColor(this, if (error) R.color.danger else R.color.text_primary))
        if (error) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showHelpDialog() {
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(R.layout.dialog_help)
        dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)?.background = ColorDrawable(Color.TRANSPARENT)
        dialog.findViewById<MaterialButton>(R.id.btnCloseHelp)?.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private data class DemoStep(val command: MovementCommand, val durationMs: Long)

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 450L
        private const val GYRO_THRESHOLD = 16f
        private const val GYRO_NEUTRAL = 8f
        private const val GYRO_SEND_INTERVAL_MS = 180L
        private const val VOICE_RESTART_DELAY_MS = 250L
        private val DEMO_SEQUENCE = listOf(
            DemoStep(MovementCommand.FORWARD, 1400L),
            DemoStep(MovementCommand.FORWARD_RIGHT, 850L),
            DemoStep(MovementCommand.RIGHT, 700L),
            DemoStep(MovementCommand.BACKWARD_RIGHT, 850L),
            DemoStep(MovementCommand.BACKWARD, 1200L),
            DemoStep(MovementCommand.BACKWARD_LEFT, 850L),
            DemoStep(MovementCommand.LEFT, 700L),
            DemoStep(MovementCommand.FORWARD_LEFT, 850L),
            DemoStep(MovementCommand.FORWARD, 900L),
            DemoStep(MovementCommand.STOP, 300L)
        )
    }
}
