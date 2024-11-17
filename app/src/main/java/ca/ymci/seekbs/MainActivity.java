package ca.ymci.seekbs;

import android.content.Context;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Toast;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import io.javalin.Javalin;
import org.opencv.android.CameraActivity;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.Imgproc;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.List;

public class MainActivity extends CameraActivity implements CameraBridgeViewBase.CvCameraViewListener2 {
    private static final String TAG = "ImageMatcher";

    private CameraBridgeViewBase mOpenCvCameraView;
    private int frameWidth = 0;
    private int frameHeight = 0;

    private int lastMatchIndex = 6;
    private float lastMatchConfidence = 0.0f;

    private long lastRun = System.currentTimeMillis();

    private Interpreter interpreter;

    private UsbSerialPort port;

    public MainActivity() {
        Log.i(TAG, "Instantiated new " + this.getClass());
    }

    private void createInterpreter() {
        Interpreter.Options options = new Interpreter.Options();
        try {
            this.interpreter = new Interpreter(
                    FileUtil.loadMappedFile(this, "model_unquant.tflite"),
                    options
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called onCreate");
        super.onCreate(savedInstanceState);

        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully");
            initializeOpenCV();
        } else {
            Log.e(TAG, "OpenCV initialization failed!");
            Toast.makeText(this, "OpenCV initialization failed!", Toast.LENGTH_LONG).show();
            return;
        }


//        Javalin javalin = Javalin.create()
//                .before((ctx) -> {
//                    Log.i(TAG, "Received request: " + ctx.method() + " " + ctx.path());
//                })
//                .get("/", ctx -> ctx.result("Hello, World!"))
//                .post("/api/control", ctx -> {
//                    try {
//                        Log.i(TAG, "Received control frame: " + ctx.body());
//                        port.write(ctx.body().getBytes(), 1000);
//                        Log.i(TAG, "Wrote control frame to serial port  ");
//                    } catch (IOException e) {
//                        Log.e(TAG, "Error writing to serial port: " + e.getMessage());
//                        ctx.result("Error: " + e.getMessage());
//                        return;
//                    }
//                    ctx.result("OK");
//                })
//                .start(1337);
//        Log.i(TAG, "Javalin started");

        createInterpreter();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        mOpenCvCameraView = findViewById(R.id.tutorial1_activity_java_surface_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);
        /*

        // Find all available drivers from attached devices.
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        if (availableDrivers.isEmpty()) {
            return;
        }


        // Open a connection to the first available driver.
        UsbSerialDriver driver = availableDrivers.get(0);
        UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
        if (connection == null) {
            // add UsbManager.requestPermission(driver.getDevice(), ..) handling here
            return;
        }
        port = driver.getPorts().get(0); // Most devices have just one port (port 0)
        try {
            port.open(connection);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            port.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }*/
    }

    private void initializeOpenCV() {
    }


    @Override
    protected void onPause() {
        super.onPause();
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.disableView();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.enableView();
        }
    }

    @Override
    protected List<CameraBridgeViewBase> getCameraViewList() {
        return Collections.singletonList(mOpenCvCameraView);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.disableView();
        }
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
        frameWidth = width;
        frameHeight = height;
    }

    @Override
    public void onCameraViewStopped() {
        // Implementation for camera view stopped
    }


    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        Mat rgba = inputFrame.rgba();

        if (System.currentTimeMillis() - lastRun < 1000) {
            Imgproc.putText(
                    rgba,
                    "Match: " + this.lastMatchIndex + " Confidence: " + this.lastMatchConfidence,
                    new Point(10, 50),
                    Imgproc.FONT_HERSHEY_COMPLEX,
                    1.0,
                    new Scalar(255, 0, 0),
                    2
            );
            return rgba;
        }

        lastRun = System.currentTimeMillis();

        // create a smaller Mat for the model input
        Mat resized = new Mat();
        Size sz = new Size(224, 224);
        Imgproc.resize(rgba, resized, sz);

        // convert to RGB format (from RGBA)
        Mat rgb = new Mat();
        Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_RGBA2RGB);

        // normalize pixel values to float
        Mat normalized = new Mat();
        rgb.convertTo(normalized, CvType.CV_32FC3, 1.0 / 255.0);

        // create byte buffer
        int modelInputSize = 224 * 224 * 3 * 4; // width * height * channels * bytes per float
        ByteBuffer imgData = ByteBuffer.allocateDirect(modelInputSize);
        imgData.order(ByteOrder.nativeOrder());

        // fill the buffer
        float[] pixels = new float[3];
        for (int i = 0; i < normalized.rows(); i++) {
            for (int j = 0; j < normalized.cols(); j++) {
                normalized.get(i, j, pixels);
                // TensorFlow expects RGB order
                imgData.putFloat(pixels[0]); // R
                imgData.putFloat(pixels[1]); // G
                imgData.putFloat(pixels[2]); // B
            }
        }

        // reset position to beginning for reading
        imgData.rewind();


        float[][] output = new float[1][7];

        // run inference, do all the magic
        interpreter.run(imgData, output);

        float max = output[0][0];
        int maxIndex = 0;
        for (int i = 1; i < 7; i++) {
            if (output[0][i] > max) {
                max = output[0][i];
                maxIndex = i;
            }
        }

        this.lastMatchIndex = maxIndex;
        this.lastMatchConfidence = max;

//        Log.i(TAG, "Max: " + max + " Index: " + maxIndex);
//        // print entire array
//        Log.i(TAG,"-----");
//        for (int i = 0; i < 7; i++) {
//            Log.i(TAG, "Index: " + i + " Value: " + output[0][i]);
//        }

        Log.i(TAG,"Sending hi");
        if (port != null) {
            try {
                port.write("{\"speed_left\":200,\"direction_left\":0,\"speed_right\":200,\"direction_right\":1}".getBytes(), 1000);
            } catch (IOException e) {
                Log.e(TAG, "Error writing to serial port: " + e.getMessage());
            }

            Imgproc.putText(
                    rgba,
                    "Sending hi",
                    new Point(10, 100),
                    Imgproc.FONT_HERSHEY_COMPLEX,
                    1.0,
                    new Scalar(0, 255, 0),
                    2
            );
            byte[] buff = new byte[100];
            try {
                port.read(buff, 1000);
                Log.i(TAG, "Read from serial port: " + new String(buff));
                Imgproc.putText(
                        rgba,
                        "Read from serial port: " + new String(buff),
                        new Point(10, 150),
                        Imgproc.FONT_HERSHEY_COMPLEX,
                        1.0,
                        new Scalar(0, 255, 0),
                        2
                );
            } catch (IOException e) {
                Log.e(TAG, "Error reading from serial port: " + e.getMessage());
            }
        }

        // Draw the match result
        Imgproc.putText(
                rgba,
                "Match: " + maxIndex + " Confidence: " + max,
                new Point(10, 50),
                Imgproc.FONT_HERSHEY_COMPLEX,
                1.0,
                new Scalar(0, 255, 0),
                2
        );

        // clean up intermediate Mats
        resized.release();
        rgb.release();
        normalized.release();


        // drawCrosshair(rgba);

        return rgba;
    }
}