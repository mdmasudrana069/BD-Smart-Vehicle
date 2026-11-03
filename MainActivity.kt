package bd.smartvehicle

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var resultText: TextView
    private lateinit var captureButton: Button
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildScreen()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 20, 22, 16)
            setBackgroundColor(0xFFF5F7F6.toInt())
        }
        val title = TextView(this).apply {
            text = "BD SMART VEHICLE"
            textSize = 22f
            setTextColor(0xFF0B3D2E.toInt())
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "AI নম্বর প্লেট শনাক্তকরণ • পরীক্ষামূলক সংস্করণ"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 5, 0, 14)
        }
        previewView = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 260
            )
        }
        captureButton = Button(this).apply {
            text = "ক্যামেরা দিয়ে নম্বর স্ক্যান করুন"
            isEnabled = false
            setOnClickListener { takePhotoAndRead() }
        }
        resultText = TextView(this).apply {
            text = "ক্যামেরার অনুমতি দিন। এরপর গাড়ির নম্বর প্লেট ফ্রেমে এনে স্ক্যান করুন।"
            textSize = 16f
            setTextColor(0xFF202B26.toInt())
            setPadding(0, 18, 0, 10)
        }
        val note = TextView(this).apply {
            text = "গুরুত্বপূর্ণ: এটি ডেমো। এখানে নম্বর শনাক্তকরণ ও নমুনা ডেটা দেখানো হয়; BRTA-র লাইভ ডেটা বা আইনি যাচাই নয়।"
            textSize = 12f
            setTextColor(0xFF8A4B08.toInt())
            setPadding(0, 12, 0, 0)
        }
        page.addView(title)
        page.addView(subtitle)
        page.addView(previewView)
        page.addView(captureButton)
        page.addView(resultText)
        page.addView(note)
        setContentView(ScrollView(this).apply { addView(page) })
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
                captureButton.isEnabled = true
            } catch (e: Exception) {
                resultText.text = "ক্যামেরা চালু করা যায়নি: ${e.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhotoAndRead() {
        val capture = imageCapture ?: return
        val photo = File(cacheDir, "vehicle_scan.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(photo).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    resultText.text = "ছবি বিশ্লেষণ হচ্ছে..."
                    val image = InputImage.fromFilePath(this@MainActivity, android.net.Uri.fromFile(photo))
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    recognizer.process(image)
                        .addOnSuccessListener { visionText ->
                            val raw = visionText.text
                            val plate = extractPlate(raw)
                            if (plate == null) {
                                resultText.text = "নম্বর প্লেট স্পষ্টভাবে পড়া যায়নি। আলো ও ক্যামেরার দূরত্ব ঠিক করে আবার চেষ্টা করুন।\n\nপড়া লেখা: $raw"
                            } else {
                                resultText.text = demoLookup(plate) + "\n\nOCR-এ পড়া লেখা:\n$raw"
                            }
                            recognizer.close()
                        }
                        .addOnFailureListener {
                            resultText.text = "লেখা শনাক্ত করা যায়নি: ${it.localizedMessage}"
                            recognizer.close()
                        }
                }
                override fun onError(exception: ImageCaptureException) {
                    resultText.text = "ছবি তোলা যায়নি: ${exception.localizedMessage}"
                }
            })
    }

    private fun extractPlate(raw: String): String? {
        val normalized = raw.uppercase()
            .replace('০','0').replace('১','1').replace('২','2').replace('৩','3')
            .replace('৪','4').replace('৫','5').replace('৬','6').replace('৭','7')
            .replace('৮','8').replace('৯','9')
            .replace(Regex("[^A-Z0-9\\u0980-\\u09FF-]"), " ")
        val tokens = normalized.split(Regex("\\s+")).filter { it.length >= 4 }
        return tokens.firstOrNull { token -> token.any { it.isDigit() } && token.any { it.isLetter() } }
            ?: tokens.firstOrNull { it.any { ch -> ch.isDigit() } }
    }

    private fun demoLookup(plate: String): String {
        // Deliberately fictional sample records; no real vehicle or owner data.
        val sample = mapOf(
            "DHAKA-METRO-GA-11-1234" to "ডেমো রেকর্ড: রেজিস্ট্রেশন নমুনা আছে; ফিটনেস ও ট্যাক্স টোকেন: পরীক্ষার জন্য বৈধ দেখানো হয়েছে।",
            "DHAKA-METRO-CHA-22-5678" to "ডেমো রেকর্ড: পরীক্ষার জন্য ফিটনেস মেয়াদোত্তীর্ণ দেখানো হয়েছে।"
        )
        val match = sample[plate.replace(" ", "-")]
        return "শনাক্ত নম্বর: $plate\n\n" +
            (match ?: "এই নম্বরের কোনো ডেমো রেকর্ড নেই। বাস্তব তথ্য জানতে অনুমোদিত BRTA API সংযোগ প্রয়োজন।") +
            "\n\nএটি বাস্তব সরকারি যাচাই নয়।"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) startCamera()
        else resultText.text = "স্ক্যান করতে ক্যামেরার অনুমতি প্রয়োজন।"
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }
}
