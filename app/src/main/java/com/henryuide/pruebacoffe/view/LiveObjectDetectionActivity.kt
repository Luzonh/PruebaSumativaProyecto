package com.henryuide.pruebacoffe.view

import android.Manifest
import android.animation.AnimatorInflater
import android.animation.AnimatorSet
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Camera
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.google.android.gms.common.internal.Objects
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.henryuide.pruebacoffe.R
import com.henryuide.pruebacoffe.camera.CameraSource
import com.henryuide.pruebacoffe.camera.CameraSourcePreview
import com.henryuide.pruebacoffe.camera.GraphicOverlay
import com.henryuide.pruebacoffe.camera.WorkflowModel
import com.henryuide.pruebacoffe.camera.WorkflowModel.WorkflowState
import com.henryuide.pruebacoffe.objectdetection.DetectedObjectInfo
import com.henryuide.pruebacoffe.objectdetection.MultiObjectProcessor
import com.henryuide.pruebacoffe.objectdetection.ProminentObjectProcessor
import com.henryuide.pruebacoffe.pestsearch.BottomSheetScrimView
import com.henryuide.pruebacoffe.pestsearch.Pest
import com.henryuide.pruebacoffe.settings.AboutActivity
import com.henryuide.pruebacoffe.settings.PreferenceUtils
import com.henryuide.pruebacoffe.settings.SettingsActivity
import java.io.IOException
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan

private const val REQUEST_CODE_PERMISSIONS = 999 // Return code after asking for permission
private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA) // permission needed

class LiveObjectDetectionActivity : AppCompatActivity(), View.OnClickListener {
    private var cameraSource: CameraSource? = null
    private var preview: CameraSourcePreview? = null
    private var graphicOverlay: GraphicOverlay? = null
    private var settingsButton: View? = null
    private var aboutButton: View? = null
    private var flashButton: View? = null
    private var promptChip: Chip? = null
    private var promptChipAnimator: AnimatorSet? = null
    private var searchButton: ExtendedFloatingActionButton? = null
    private var searchButtonAnimator: AnimatorSet? = null
    private var workflowModel: WorkflowModel? = null
    private var currentWorkflowState: WorkflowState? = null

    private var bottomSheetBehavior: BottomSheetBehavior<View>? = null
    private var bottomSheetScrimView: BottomSheetScrimView? = null
    private var bottomSheetTitleView: TextView? = null
    private var bottomSheetTitlePest: TextView? = null
    private var bottomSheetDescriptionsPest: TextView? = null
    private var bottomSheetTemperaturePest: TextView? = null
    private var bottomSheetSeeMore: TextView? = null
    private var bottomSheetScientificNamePest: TextView? = null
    private var bottomSheetImagePest: ImageView? = null
    private var objectThumbnailForBottomSheet: Bitmap? = null
    private var slidingSheetUpFromHiddenState: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_object_detection)
        promptChip = findViewById(R.id.bottom_prompt_chip)
        promptChipAnimator = (AnimatorInflater.loadAnimator(
            this,
            R.animator.bottom_prompt_chip_enter
        ) as AnimatorSet).apply { setTarget(promptChip) }
        searchButton =
            findViewById<ExtendedFloatingActionButton>(R.id.product_search_button).apply {
                setOnClickListener(this@LiveObjectDetectionActivity)
            }
        searchButtonAnimator = (AnimatorInflater.loadAnimator(
            this,
            R.animator.search_button_enter
        ) as AnimatorSet).apply { setTarget(searchButton) }
        setUpBottomSheet()
        findViewById<View>(R.id.close_button).setOnClickListener(this)
        flashButton = findViewById<View>(R.id.flash_button).apply {
            setOnClickListener(this@LiveObjectDetectionActivity)
        }
        settingsButton = findViewById<View>(R.id.settings_button).apply {
            setOnClickListener(this@LiveObjectDetectionActivity)
        }
        aboutButton = findViewById<View>(R.id.about_button).apply {
            setOnClickListener(this@LiveObjectDetectionActivity)
        }
        if (allPermissionsGranted()) {
            initCameraComponents()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun initCameraComponents() {
        preview = findViewById(R.id.camera_preview)
        graphicOverlay = findViewById<GraphicOverlay>(R.id.camera_preview_graphic_overlay).apply {
            setOnClickListener(this@LiveObjectDetectionActivity)
            cameraSource = CameraSource(this)
        }
        setUpWorkflowModel()
    }

    private fun allPermissionsGranted(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                initCameraComponents()
            } else {
                Toast.makeText(this, getString(R.string.permission_deny_text), Toast.LENGTH_SHORT)
                    .show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        workflowModel?.markCameraFrozen()
        settingsButton?.isEnabled = true
        aboutButton?.isEnabled = true
        bottomSheetBehavior?.state = BottomSheetBehavior.STATE_HIDDEN
        currentWorkflowState = WorkflowState.NOT_STARTED
        cameraSource?.setFrameProcessor(
            if (PreferenceUtils.isMultipleObjectsMode(this)) {
                MultiObjectProcessor(
                    graphicOverlay!!, workflowModel!!,
                    CUSTOM_MODEL_PATH,
                )
            } else {
                ProminentObjectProcessor(
                    graphicOverlay!!, workflowModel!!,
                    CUSTOM_MODEL_PATH,
                )
            }
        )
        workflowModel?.setWorkflowState(WorkflowState.DETECTING)
    }

    override fun onPause() {
        super.onPause()
        currentWorkflowState = WorkflowState.NOT_STARTED
        stopCameraPreview()
        errorSnackbar?.dismiss()
    }

    override fun onDestroy() {
        super.onDestroy()
        errorSnackbar?.dismiss()
        cameraSource?.release()
        cameraSource = null
    }

    override fun onBackPressed() {
        if (bottomSheetBehavior?.state != BottomSheetBehavior.STATE_HIDDEN) {
            bottomSheetBehavior?.setState(BottomSheetBehavior.STATE_HIDDEN)
        } else {
            super.onBackPressed()
        }
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.product_search_button -> {
                searchButton?.isEnabled = false
                workflowModel?.onSearchButtonClicked()
            }
            R.id.bottom_sheet_scrim_view -> bottomSheetBehavior?.setState(BottomSheetBehavior.STATE_HIDDEN)
            R.id.close_button -> onBackPressed()
            R.id.flash_button -> {
                if (flashButton?.isSelected == true) {
                    flashButton?.isSelected = false
                    cameraSource?.updateFlashMode(Camera.Parameters.FLASH_MODE_OFF)
                } else {
                    flashButton?.isSelected = true
                    cameraSource?.updateFlashMode(Camera.Parameters.FLASH_MODE_TORCH)
                }
            }
            R.id.settings_button -> {
                settingsButton?.isEnabled = false
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            R.id.about_button -> {
                aboutButton?.isEnabled = false
                startActivity(Intent(this, AboutActivity::class.java))
            }
        }
    }

    private fun startCameraPreview() {
        val cameraSource = this.cameraSource ?: return
        val workflowModel = this.workflowModel ?: return
        if (!workflowModel.isCameraLive) {
            try {
                workflowModel.markCameraLive()
                preview?.start(cameraSource)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start camera preview!", e)
                cameraSource.release()
                this.cameraSource = null
            }
        }
    }

    private fun stopCameraPreview() {
        if (workflowModel?.isCameraLive == true) {
            workflowModel!!.markCameraFrozen()
            flashButton?.isSelected = false
            preview?.stop()
        }
    }

    private fun setUpBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(findViewById(R.id.bottom_sheet))

        bottomSheetBehavior?.setBottomSheetCallback(
            object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    Log.d(TAG, "Bottom sheet new state: $newState")
                    bottomSheetScrimView?.visibility =
                        if (newState == BottomSheetBehavior.STATE_HIDDEN) View.GONE else View.VISIBLE
                    graphicOverlay?.clear()

                    when (newState) {
                        BottomSheetBehavior.STATE_HIDDEN -> workflowModel?.setWorkflowState(
                            WorkflowState.DETECTING
                        )
                        BottomSheetBehavior.STATE_COLLAPSED,
                        BottomSheetBehavior.STATE_EXPANDED,
                        BottomSheetBehavior.STATE_HALF_EXPANDED -> slidingSheetUpFromHiddenState =
                            false
                        BottomSheetBehavior.STATE_DRAGGING, BottomSheetBehavior.STATE_SETTLING -> {
                        }
                    }
                }

                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                    val searchedObject = workflowModel!!.searchedObject.value
                    if (searchedObject == null || java.lang.Float.isNaN(slideOffset)) {
                        return
                    }

                    val graphicOverlay = graphicOverlay ?: return
                    val bottomSheetBehavior = bottomSheetBehavior ?: return
                    val collapsedStateHeight =
                        bottomSheetBehavior.peekHeight.coerceAtMost(bottomSheet.height)
                    val bottomBitmap = objectThumbnailForBottomSheet ?: return
                    if (slidingSheetUpFromHiddenState) {
                        val thumbnailSrcRect =
                            graphicOverlay.translateRect(searchedObject.boundingBox)
                        bottomSheetScrimView?.updateWithThumbnailTranslateAndScale(
                            bottomBitmap,
                            collapsedStateHeight,
                            slideOffset,
                            thumbnailSrcRect
                        )
                    } else {
                        bottomSheetScrimView?.updateWithThumbnailTranslate(
                            bottomBitmap, collapsedStateHeight, slideOffset, bottomSheet
                        )
                    }
                }
            })

        bottomSheetScrimView =
            findViewById<BottomSheetScrimView>(R.id.bottom_sheet_scrim_view).apply {
                setOnClickListener(this@LiveObjectDetectionActivity)
            }

        bottomSheetTitleView = findViewById(R.id.bottom_sheet_title)
        bottomSheetTitlePest = findViewById(R.id.tvTitle)
        bottomSheetImagePest = findViewById(R.id.imageView)
        bottomSheetDescriptionsPest = findViewById(R.id.tvDescriptions)
        bottomSheetTemperaturePest = findViewById(R.id.tvTemperature)
        bottomSheetScientificNamePest = findViewById(R.id.tvScientificName)
        bottomSheetSeeMore = findViewById(R.id.tvSeeMore)
    }


    private fun setUpWorkflowModel() {
        workflowModel = ViewModelProviders.of(this).get(WorkflowModel::class.java).apply {

            // Observes the workflow state changes, if happens, update the overlay view indicators and
            // camera preview state.
            workflowState.observe(this@LiveObjectDetectionActivity, Observer { workflowState ->
                if (workflowState == null || Objects.equal(currentWorkflowState, workflowState)) {
                    return@Observer
                }
                currentWorkflowState = workflowState
                Log.d(TAG, "Current workflow state: ${workflowState.name}")

                if (PreferenceUtils.isAutoSearchEnabled(this@LiveObjectDetectionActivity)) {
                    stateChangeInAutoSearchMode(workflowState)
                } else {
                    stateChangeInManualSearchMode(workflowState)
                }
            })

            // product search results.

            objectToSearch.observe(this@LiveObjectDetectionActivity) { detectObject ->

                // Filtrar objetos que no sean hojas de café

                val validLabels = listOf(
                    getString(R.string.id_antracnosis),
                    getString(R.string.id_ojo_de_gallo),
                    getString(R.string.id_roya),
                    getString(R.string.id_miner)
                )
                val isCoffeeLeaf = isCoffeeLeafDetected(detectObject)
                val detectedLabel = detectObject.labels.firstOrNull()?.text ?: ""

                // Check if the detected object is a coffee leaf and has a valid disease/pest label
                if (isCoffeeLeaf && detectedLabel in validLabels) {
                    val pestList: List<Pest> = detectObject.labels.map { label ->
                        //data class Info(val mainInfo: String, val extraInfo: String)
                        val information = when (label.text) {
                            getString(R.string.id_antracnosis) -> getString(R.string.msg_antracnosis)
                            getString(R.string.id_ojo_de_gallo) -> getString(R.string.msg_ojo_de_gallo)
                            getString(R.string.id_roya) -> getString(R.string.msg_roya )


                            getString(R.string.id_miner) -> getString(R.string.msg_miner)
                            else -> getString(R.string.msg_default)
                        }
                        val scientificName = when (label.text) {
                            getString(R.string.id_antracnosis) -> getString(R.string.scientific_name_antracnosis)
                            getString(R.string.id_ojo_de_gallo) -> getString(R.string.scientific_name_ojo_de_gallo)
                            getString(R.string.id_roya) -> getString(R.string.scientific_name_roya)
                            getString(R.string.id_miner) -> getString(R.string.scientific_name_miner)
                            else -> getString(R.string.msg_default)
                        }
                        val imgPreview = when (label.text) {
                            getString(R.string.id_antracnosis) -> R.drawable.antraconosis
                            getString(R.string.id_ojo_de_gallo) -> R.drawable.ojo_de_gallo
                            getString(R.string.id_roya) -> R.drawable.roya
                            getString(R.string.id_miner) -> R.drawable.minador
                            else -> R.drawable.default_leaf
                        }
                        val temperature = when (label.text) {
                            getString(R.string.id_antracnosis) -> getString(R.string.temperature_antracnosis)
                            getString(R.string.id_ojo_de_gallo) -> getString(R.string.temperature_ojo_de_gallo)
                            getString(R.string.id_roya) -> getString(R.string.temperature_roya)
                            getString(R.string.id_miner) -> getString(R.string.temperature_roya)
                            else -> getString(R.string.msg_default)
                        }
                        val moreInformation = when (label.text) {
                            getString(R.string.id_antracnosis) -> "https://perfectdailygrind.com/es/2021/04/27/una-enfermedad-silenciosa-que-es-la-antracnosis-del-cafe/"
                            getString(R.string.id_ojo_de_gallo) -> "http://royacafe.lanref.org.mx/Documentos/FTNo49Mycenacitricolor.pdf"
                            getString(R.string.id_roya) -> "https://cropaia.com/es/blog/la-roya/"
                            getString(R.string.id_miner) -> "https://www.agronegocios.co/agricultura/consejos-del-profesor-yarumo-el-minador-de-la-hoja-de-cafe-2975523"
                            else -> getString(R.string.msg_default)
                        }
                        Pest(label.text, scientificName, information, imgPreview, temperature, moreInformation)
                    }
                    workflowModel?.onSearchCompleted(detectObject, pestList)
                }else if (!isCoffeeLeaf) {
                    // Show error message if no coffee leaf is detected
                    showErrorMessage("No se ha detectado una hoja de café. Por favor, enfoque correctamente la hoja de café.")

                } else {
                    // Show error message for invalid object
                    showErrorMessage("No se ha detectado una hoja de café válida. Por favor, enfoque correctamente el objeto.")
                }
            }

            // to present search result.
            searchedObject.observe(this@LiveObjectDetectionActivity) { searchedObject ->

                val productList = searchedObject.pestList
                objectThumbnailForBottomSheet = searchedObject.getObjectThumbnail()
                getString(R.string.detected)
                /*
                bottomSheetTitleView?.text = resources.getQuantityString(
                    R.plurals.bottom_sheet_title,
                    //R.string.detected,

                    productList.size,
                    productList.size
                )*/

                if (productList.isEmpty() || !productList.any { it.title.toLowerCase().contains("") }) {
                    // No se detectó ninguna hoja o la lista está vacía
                    showErrorMessage(getString(R.string.error_no_coffee_leaf))
                } else {
                    objectThumbnailForBottomSheet = searchedObject.getObjectThumbnail()
                    getString(R.string.detected)
                    /*
                    bottomSheetTitleView?.text = resources.getQuantityString(
                        R.plurals.bottom_sheet_title,
                        productList.size,
                        productList.size
                    )*/

                    bottomSheetTitlePest?.text = productList.firstOrNull()?.title
                        ?: getString(R.string.msg_default) //Load Title
                    bottomSheetImagePest?.setImageResource(
                        productList.firstOrNull()?.imageUrl ?: R.drawable.tfl2_logo
                    ) //Load ImagePreview
                    bottomSheetDescriptionsPest?.text = (productList.firstOrNull()?.description
                        ?: getString(R.string.msg_default)) //Load Description

                    bottomSheetScientificNamePest?.text = (productList.firstOrNull()?.subtitle
                        ?: getString(R.string.msg_default)) //Load Scientific Name
                    bottomSheetTemperaturePest?.text = (productList.firstOrNull()?.temperature
                        ?: getString(R.string.msg_default)) //Load Scientific Name
                    bottomSheetSeeMore?.setOnClickListener {
                        openBrowser(
                            productList.firstOrNull()?.urlInformation
                                ?: getString(R.string.msg_default)
                        )
                    }
                    slidingSheetUpFromHiddenState = true
                    bottomSheetBehavior?.peekHeight =
                        preview?.height?.div(1) ?: BottomSheetBehavior.PEEK_HEIGHT_AUTO
                    bottomSheetBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED
                }
            }
        }
    }

    private var errorSnackbar: Snackbar? = null
    private fun showErrorMessage(message: String) {
        // Ocultar el bottom sheet si está visible
        bottomSheetBehavior?.state = BottomSheetBehavior.STATE_HIDDEN
        errorSnackbar?.dismiss()
        // Mostrar un mensaje de error, por ejemplo, usando un Snackbar
        errorSnackbar = Snackbar.make(findViewById(android.R.id.content),message, Snackbar.LENGTH_INDEFINITE)
            .setAction(getString(R.string.action_retry)) {
                // Acción para reintentar la detección
                retryDetection()
                // Descartar el Snackbar inmediatamente al presionar "Reintentar"
                errorSnackbar?.dismiss()

            }
        errorSnackbar?.show()

    }
    private fun isCoffeeLeafDetected(detectObject: DetectedObjectInfo): Boolean {

        return detectObject.labels.any { label ->
            //label.text in listOf((R.string.id_roya)
            label.text == getString(R.string.id_roya)
            //label.text == getString(R.string.id_antracnosis)
            //label.text == getString(R.string.id_ojo_de_gallo)
            //label.text == getString(R.string.id_miner) // Assume this is a valid label for coffee leaf

        }
    }
    private fun retryDetection() {
        // Descartar el Snackbar de error si está visible
        errorSnackbar?.dismiss()
        errorSnackbar = null

        // Reiniciar el proceso de detección
        workflowModel?.markCameraFrozen()
        settingsButton?.isEnabled = true
        aboutButton?.isEnabled = true
        bottomSheetBehavior?.state = BottomSheetBehavior.STATE_HIDDEN
        currentWorkflowState = WorkflowState.NOT_STARTED
        cameraSource?.setFrameProcessor(
            if (PreferenceUtils.isMultipleObjectsMode(this)) {
                MultiObjectProcessor(
                    graphicOverlay!!, workflowModel!!,
                    CUSTOM_MODEL_PATH,

                    )
            } else {
                ProminentObjectProcessor(
                    graphicOverlay!!, workflowModel!!,
                    CUSTOM_MODEL_PATH,

                    )
            }
        )
        workflowModel?.setWorkflowState(WorkflowState.DETECTING)
    }


    private fun openBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    private fun stateChangeInAutoSearchMode(workflowState: WorkflowState) {
        val wasPromptChipGone = promptChip!!.visibility == View.GONE

        searchButton?.visibility = View.GONE
        when (workflowState) {
            WorkflowState.DETECTING, WorkflowState.DETECTED, WorkflowState.CONFIRMING -> {
                promptChip?.visibility = View.VISIBLE
                promptChip?.setText(
                    if (workflowState == WorkflowState.CONFIRMING)
                        R.string.prompt_hold_camera_steady
                    else
                        R.string.prompt_point_at_a_bird
                )
                startCameraPreview()
            }
            WorkflowState.CONFIRMED -> {
                promptChip?.visibility = View.VISIBLE
                promptChip?.setText(R.string.prompt_searching)
                stopCameraPreview()
            }
            WorkflowState.SEARCHING -> {
                promptChip?.visibility = View.GONE
                stopCameraPreview()
            }
            WorkflowState.SEARCHED -> {
                stopCameraPreview()
            }
            else -> promptChip?.visibility = View.GONE
        }

        val shouldPlayPromptChipEnteringAnimation =
            wasPromptChipGone && promptChip?.visibility == View.VISIBLE
        if (shouldPlayPromptChipEnteringAnimation && promptChipAnimator?.isRunning == false) {
            promptChipAnimator?.start()
        }
    }

    private fun stateChangeInManualSearchMode(workflowState: WorkflowState) {
        val wasPromptChipGone = promptChip?.visibility == View.GONE
        val wasSearchButtonGone = searchButton?.visibility == View.GONE

        when (workflowState) {
            WorkflowState.DETECTING, WorkflowState.DETECTED, WorkflowState.CONFIRMING -> {
                promptChip?.visibility = View.VISIBLE
                promptChip?.setText(R.string.prompt_point_at_an_object)
                searchButton?.visibility = View.GONE
                startCameraPreview()
            }
            WorkflowState.CONFIRMED -> {
                promptChip?.visibility = View.GONE
                searchButton?.visibility = View.VISIBLE
                searchButton?.isEnabled = true
                searchButton?.setBackgroundColor(Color.WHITE)
                startCameraPreview()
            }
            WorkflowState.SEARCHING -> {
                promptChip?.visibility = View.GONE
                searchButton?.visibility = View.VISIBLE
                searchButton?.isEnabled = false
                searchButton?.setBackgroundColor(Color.GRAY)
                stopCameraPreview()
            }
            WorkflowState.SEARCHED -> {
                promptChip?.visibility = View.GONE
                searchButton?.visibility = View.GONE
                stopCameraPreview()
            }
            else -> {
                promptChip?.visibility = View.GONE
                searchButton?.visibility = View.GONE
            }
        }

        val shouldPlayPromptChipEnteringAnimation =
            wasPromptChipGone && promptChip?.visibility == View.VISIBLE
        promptChipAnimator?.let {
            if (shouldPlayPromptChipEnteringAnimation && !it.isRunning) it.start()
        }

        val shouldPlaySearchButtonEnteringAnimation =
            wasSearchButtonGone && searchButton?.visibility == View.VISIBLE
        searchButtonAnimator?.let {
            if (shouldPlaySearchButtonEnteringAnimation && !it.isRunning) it.start()
        }
    }

    companion object {
        private const val TAG = "LiveObjectDetection"
        //private const val CUSTOM_MODEL_PATH = "plagas_detector_v1.tflite"
        private const val CUSTOM_MODEL_PATH = "enfermedades_cafe.tflite"


    }
}