package com.squidster.furnituretester

import android.graphics.Color
import android.media.CamcorderProfile
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.core.view.accessibility.AccessibilityRecordCompat.setSource
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.filament.Box
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.ar.core.Anchor
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.ViewRenderable
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.TransformableNode
import com.squidster.initialarsetup.Model
import com.squidster.initialarsetup.ModelAdpater
import com.squidster.initialarsetup.PhotoSaver
import com.squidster.initialarsetup.VideoRecorder
import kotlinx.android.synthetic.main.activity_main.*
import java.security.cert.TrustAnchor
import java.util.concurrent.CompletableFuture


private const val BOTTOM_SHEET_PEEK_HEIGHT = 50f
private const val DOUBLE_TAP_TOLERANCE_MS = 1000L


class MainActivity : AppCompatActivity() {

    lateinit var arFragment: ArFragment

    private val models = mutableListOf(
        Model(R.drawable.chair, "Chair", R.raw.chair),
        Model(R.drawable.oven, "Oven", R.raw.oven),
        Model(R.drawable.piano, "Piano", R.raw.piano),
        Model(R.drawable.table, "Table", R.raw.table)

    )

    private lateinit var selectedModel: Model

    val viewNodes = mutableListOf<Node>()

    private lateinit var photoSaver: PhotoSaver
    private lateinit var videoRecorder: VideoRecorder

    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        arFragment = fragment as ArFragment

        setupBottomSheet()
        setUpRecycleView()
        setupDoubleTapArPlaneListener()

        photoSaver = PhotoSaver(this)
        videoRecorder = VideoRecorder(this).apply{
            sceneView = arFragment.arSceneView
            setVideoQuality(CamcorderProfile.QUALITY_1080P, resources.configuration.orientation)
        }

        setUpFab()

        getCurrentScene().addOnUpdateListener {
            rotateViewNodesTowardsUser()
        }
    }

    private fun setUpFab(){
        fab.setOnClickListener{
            if(!isRecording){
                photoSaver.takePhoto(arFragment.arSceneView)
            }
        }
        fab.setOnLongClickListener{
            isRecording = videoRecorder.toggleRecordingState()
            true
        }
        fab.setOnTouchListener{view, motionEvent ->
            if(motionEvent.action == MotionEvent.ACTION_UP && isRecording){
                // The user lifted their finger off the button, stop the recording and save
                isRecording = videoRecorder.toggleRecordingState()
                Toast.makeText(this, "Saved video to gallery!", Toast.LENGTH_LONG).show()
                true
            } else {
                false
            }
        }
    }

    private fun setupDoubleTapArPlaneListener() {
        var firstTapTime = 0L

        arFragment.setOnTapArPlaneListener { hitResult, plane, motionEvent ->
            // We only want to render on a double tap
            if (firstTapTime == 0L) {
                firstTapTime = System.currentTimeMillis()
            } else if ((System.currentTimeMillis() - firstTapTime) < DOUBLE_TAP_TOLERANCE_MS) {
                firstTapTime = 0L
                loadModel { modelRenderable, viewRenderable ->
                    addNodeToScene(hitResult.createAnchor(), modelRenderable, viewRenderable)
                }
            } else {
                firstTapTime = System.currentTimeMillis()
            }
        }
    }

    private fun setUpRecycleView() {
        rvModels.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvModels.adapter = ModelAdpater(models).apply {
            selectedModel.observe(this@MainActivity, Observer {
                this@MainActivity.selectedModel = it
                val newTitle = "Models (${it.title})"
                tvModel.text = newTitle
            })
        }
    }

    private fun setupBottomSheet() {
        val bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)

        // since peek height works with pixels and not dp we gotta work like this to set the property
        bottomSheetBehavior.peekHeight =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                BOTTOM_SHEET_PEEK_HEIGHT,
                resources.displayMetrics
            ).toInt()

        bottomSheetBehavior.addBottomSheetCallback(object :
            BottomSheetBehavior.BottomSheetCallback() {
            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                bottomSheet.bringToFront()
            }

            override fun onStateChanged(bottomSheet: View, newState: Int) {}
        })
    }

    private fun getCurrentScene() = arFragment.arSceneView.scene

    private fun createDeleteButton(): Button {
        return Button(this).apply {
            text = "Delete"
            setBackgroundColor(Color.RED)
            setTextColor(Color.WHITE)
        }
    }

    // This method will correct the delete button to always be pointed towards the user
    private fun rotateViewNodesTowardsUser(){
        for( node in viewNodes){
            node.renderable?.let{
                val cameraPosition = getCurrentScene().camera.worldPosition
                val viewNodePosition = node.worldPosition
                val direction = Vector3.subtract(cameraPosition, viewNodePosition)
                node.worldRotation = Quaternion.lookRotation(direction, Vector3.up())
            }
        }
    }

    // Hierarchy of nodes: anchor -> model -> view
    private fun addNodeToScene(
        anchor: Anchor,
        modelRenderable: ModelRenderable,
        viewRenderable: ViewRenderable
    ) {
        // This node is our 3D furniture model
        val anchorNode = AnchorNode(anchor)
        val modelNode = TransformableNode(arFragment.transformationSystem).apply {
            renderable = modelRenderable
            setParent(anchorNode)
            getCurrentScene().addChild(anchorNode)
            select()
        }
        val viewNode = Node().apply {
            renderable = null
            setParent(modelNode)
            // This helps us determine where we will position the delete button for rendered items
            val box = modelNode.renderable?.collisionShape as com.google.ar.sceneform.collision.Box
            localPosition = Vector3(0f, box.size.y, 0f)
            (viewRenderable.view as Button).setOnClickListener {
                // By deleted the anchor, we also remove any children it has (the model and view)
                getCurrentScene().removeChild(anchorNode)
                viewNodes.remove(this)
            }
        }

        viewNodes.add(viewNode)
        modelNode.setOnTapListener { _, _ ->
            if (!modelNode.isTransforming) {
                if (viewNode.renderable == null) {
                    viewNode.renderable = viewRenderable
                } else {
                    viewNode.renderable
                }
            }
        }
    }

    private fun loadModel(callback: (ModelRenderable, ViewRenderable) -> Unit) {
        val modelRenderable = ModelRenderable.builder()
            .setSource(this, selectedModel.modelResourceId)
            .build()

        val viewRenderable = ViewRenderable.builder()
            .setView(this, createDeleteButton())
            .build()

        // We will wait for the renderables to finish then add them to the callback, doing this this way since we are waiting on 2 renderables
        // If it was just one, use .thenAccept after the build for the one item
        CompletableFuture.allOf(modelRenderable, viewRenderable)
            .thenAccept {
                callback(modelRenderable.get(), viewRenderable.get())
            }
            .exceptionally {
                Toast.makeText(this, "Error loading model: $it", Toast.LENGTH_LONG).show()
                null
            }
    }
}