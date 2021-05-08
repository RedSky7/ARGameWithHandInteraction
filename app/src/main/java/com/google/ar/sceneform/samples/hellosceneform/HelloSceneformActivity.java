package com.google.ar.sceneform.samples.hellosceneform;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.RegionIterator;
import android.graphics.drawable.BitmapDrawable;
import android.media.Image;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.ar.core.Anchor;
import com.google.ar.core.Config;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Pose;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.ux.ArFragment;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfInt4;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import timber.log.Timber;

import static com.google.ar.sceneform.samples.hellosceneform.HelloSceneformActivity.STATE.OPEN_HAND;
import static org.opencv.core.CvType.CV_8UC1;
import static org.opencv.imgproc.Imgproc.cvtColor;

public class HelloSceneformActivity extends AppCompatActivity {

    private static final double MIN_OPENGL_VERSION = 3.0;

    static {
        System.loadLibrary("opencv_java3");
    }

    private ArFragment arFragment;
    private ModelRenderable boxRendarable;
    private ModelRenderable redAndyRenderable;

    private boolean droidsBuild = false;

    private float minArea = Float.MAX_VALUE;
    private ArrayList<Float> areaList = new ArrayList<>();
    private ArrayList<Point> ratios = new ArrayList<>();

    private STATE currentState = STATE.OUTSIDE;

    private TextView scoreTextView;
    private ImageView imageView;
    private ProgressBar progressBar;

    private float lowerH = 0, upperH = 15;
    private float lowerL = 0.1f * 255f, upperL = 0.9f * 255f;
    private float lowerS = 0.05f * 255f, upperS = 0.8f * 255f;

    private Scalar lowerThreshold = new Scalar(lowerH, lowerL, lowerS);
    private Scalar upperThreshold = new Scalar(upperH, upperL, upperS);

    private Bird currentBird;

    public static float xRatio, yRatio;

    float power = 0;
    private int score = 0;

    private Random random;
    private Anchor userOrigin;

    public static boolean canShoot = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!checkIsSupportedDeviceOrFinish(this)) {
            return;
        }

        setContentView(R.layout.activity_ux);

        arFragment = (ArFragment) getSupportFragmentManager().findFragmentById(R.id.ux_fragment);
        scoreTextView = findViewById(R.id.score);
        progressBar = findViewById(R.id.progressBar);

        random = new Random();

        // When you build a Renderable, Sceneform loads its resources in the background while returning
        // a CompletableFuture. Call thenAccept(), handle(), or check isDone() before calling get().
        ModelRenderable.builder()
            .setSource(this, R.raw.box)
            .build()
            .thenAccept(renderable -> boxRendarable = renderable)
            .exceptionally(
                throwable -> {
                    Toast toast = Toast.makeText(this, "Unable to load andy renderable", Toast.LENGTH_LONG);
                    toast.setGravity(Gravity.CENTER, 0, 0);
                    toast.show();
                    return null;
            });

        ModelRenderable.builder()
                .setSource(this, R.raw.red_andy)
                .build()
                .thenAccept(renderable -> redAndyRenderable = renderable)
                .exceptionally(
                        throwable -> {
                            Toast toast = Toast.makeText(this, "Unable to load andy renderable", Toast.LENGTH_LONG);
                            toast.setGravity(Gravity.CENTER, 0, 0);
                            toast.show();
                            return null;
                        });


        arFragment.setOnTapArPlaneListener(
            (HitResult hitResult, Plane plane, MotionEvent motionEvent) -> {
                if (boxRendarable == null || droidsBuild) {
                    return;
                }

                Config config = arFragment.getArSceneView().getSession().getConfig();
                config.setFocusMode(Config.FocusMode.AUTO);
                arFragment.getArSceneView().getSession().configure(config);


                /*List<CameraConfig> list = arFragment.getArSceneView().getSession().getSupportedCameraConfigs();
                if(!arFragment.getArSceneView().getSession().getCameraConfig().getImageSize().equals(list.get(2)))
                    arFragment.getArSceneView().getSession().setCameraConfig(list.get(2));*/

                /*if(arFragment.getArSceneView().getScene().getChildren().size() == 0) {
                    currentBird = null;
                }*/

                if (currentBird == null) {
                    currentBird = new Bird(arFragment.getArSceneView());
                    currentBird.setParent(arFragment.getArSceneView().getScene());
                    currentBird.setRenderable(redAndyRenderable);
                }

                Timber.d("focusMode = %s", arFragment.getArSceneView().getSession().getConfig().getFocusMode());

                // Create the Anchor.
                userOrigin = hitResult.createAnchor();

                if (!droidsBuild) {
                    buildNDroids(5);
                    droidsBuild = true;
                }
        });

        imageView = findViewById(R.id.imageView);

        // We must run everything on a seperate thread.
        new Thread() {
            @Override
            public void run() {
                while (true) {
                    try {
                        if (arFragment.getArSceneView().getArFrame() == null) {
                            Timber.d("ArFrame = null");
                            continue;
                        }

                        final Image image = arFragment.getArSceneView().getArFrame().acquireCameraImage();
                        if (image == null) {
                            Timber.d("image = null");
                            continue;
                        }

                        byte[] nv21;
                        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
                        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
                        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

                        int ySize = yBuffer.remaining();
                        int uSize = uBuffer.remaining();
                        int vSize = vBuffer.remaining();

                        nv21 = new byte[ySize + uSize + vSize];

                        //U and V are swapped
                        yBuffer.get(nv21, 0, ySize);
                        vBuffer.get(nv21, ySize, vSize);
                        uBuffer.get(nv21, ySize + vSize, uSize);

                        Mat mat = getYUV2Mat(image, nv21);

                        Core.transpose(mat, mat);
                        Core.flip(mat, mat, 1);


                        Timber.d("run: new image.");

                        Bitmap bmp32 = Bitmap.createBitmap(mat.width(), mat.height(), Bitmap.Config.RGB_565);
                        image.close();

                        Mat handMask = new Mat();

                        Mat hls = new Mat();
                        cvtColor(mat, hls, Imgproc.COLOR_RGB2HLS);
                        Timber.d("channels1 = %s", hls.channels());

                        Core.inRange(hls, lowerThreshold, upperThreshold, hls);

                        Imgproc.blur(hls, hls, new org.opencv.core.Size(10,10));
                        Imgproc.threshold(hls, handMask, 200, 255, Imgproc.THRESH_BINARY);

                        Utils.matToBitmap(hls, bmp32);

                        Mat hierarchy = new Mat();
                        List<MatOfPoint> contours = new ArrayList<>();
                        Imgproc.findContours(handMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

                        Paint paint = new Paint();
                        paint.setColor(canShoot ? Color.GREEN : Color.RED);
                        paint.setStrokeWidth(10);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setAntiAlias(true);

                        Bitmap destination = Bitmap.createBitmap(bmp32.getWidth(), bmp32.getHeight(), Bitmap.Config.ARGB_8888);
                        Canvas canvas = new Canvas(destination);

                        float width = bmp32.getWidth();
                        float height = bmp32.getHeight();

                        Path tempPath = new Path();
                        tempPath.addCircle(width / 2, height - width / 2, width / 2, Path.Direction.CW);
                        canvas.drawPath(tempPath, paint);
                        Region region = new Region();

                        RectF rect = new RectF();
                        tempPath.computeBounds(rect, true);

                        region.set(new android.graphics.Rect((int) rect.left, (int) rect.top, (int) rect.right, (int) rect.bottom));
                        region.setPath(tempPath, region);

                        handleHandDetection(canvas, contours, region);

                        runOnUiThread(() -> {
                            imageView.setImageDrawable(new BitmapDrawable(destination));
                            checkForCollisions();
                            Timber.d("imageSet %s", bmp32.toString());
                        });

                    } catch (Exception e) {
                        Timber.e(e);
                    }
                }
            }
        }.start();
    }

    private void shootDroid() {
        if (currentBird == null) {
            return;
        }

        Point destination;
        if (ratios.size() > 0) {
            // We must ignore the last 15 ratios as the act of opening the hand messes up the ratio.
            destination = ratios.get(Math.max(0, ratios.size() - 15));
        } else {
            destination = new Point(xRatio, yRatio);
        }

        Pose pose = arFragment.getArSceneView().getArFrame().getCamera().getPose();
        float[] forwardQuar = pose.getRotationQuaternion();
        currentBird.setVelocity(Quaternion.rotateVector(new Quaternion(forwardQuar[0], forwardQuar[1], forwardQuar[2], forwardQuar[3]),
                new Vector3(-5f + (-5f * power) + 2f * ((float) destination.y * -1), 3f * ((float) destination.x * -1), -5f * power)));
        Timber.d("shootDroid: power = %s", power);
    }

    private void buildNDroids(int n) {
        if (arFragment.getArSceneView().getSession() == null) {
            return;
        }

        for (int i = 0; i < n; i++) {
            // Create the transformable obstacle and add it to the anchor.
            Obstacle obstacle = new Obstacle();
            randomlyPlace(obstacle);
            obstacle.setRenderable(boxRendarable);
        }
    }

    private void randomlyPlace(Obstacle andy) {
        int BOUNDS = 3;
        Pose pose = userOrigin.getPose().compose(Pose.makeTranslation(random.nextInt(BOUNDS) - BOUNDS / 2f,
                random.nextInt(BOUNDS) - BOUNDS / 2f,
                -0.4f - random.nextInt(BOUNDS) - BOUNDS / 2f));
        Anchor anchor = arFragment.getArSceneView().getSession().createAnchor(pose);

        AnchorNode anchorNode = new AnchorNode(anchor);
        anchorNode.setParent(arFragment.getArSceneView().getScene());

        andy.setParent(anchorNode);
    }

    public Mat getYUV2Mat(Image image, byte[] data) {
        Mat mYuv = new Mat(image.getHeight() + image.getHeight() / 2, image.getWidth(), CV_8UC1);
        mYuv.put(0, 0, data);
        Mat mRGB = new Mat();
        cvtColor(mYuv, mRGB, Imgproc.COLOR_YUV2RGB_NV21, 3);
        return mRGB;
    }

    public static boolean checkIsSupportedDeviceOrFinish(final Activity activity) {
        String openGlVersionString = ((ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE))
                .getDeviceConfigurationInfo()
                .getGlEsVersion();
        if (Double.parseDouble(openGlVersionString) < MIN_OPENGL_VERSION) {
            Timber.e("Sceneform requires OpenGL ES 3.0 later");
            Toast.makeText(activity, "Sceneform requires OpenGL ES 3.0 or later", Toast.LENGTH_LONG).show();
            activity.finish();
            return false;
        }
        return true;
    }

    private double distance(Point point1, Point point2) {
        return Math.sqrt(Math.pow(point1.x - point2.x, 2) + Math.pow(point1.y - point2.y, 2));
    }

    enum STATE {
        FIST,
        OPEN_HAND,
        OUTSIDE,
    }

    private void reset() {
        minArea = Float.MAX_VALUE;
        areaList.clear();
        ratios.clear();
    }

    private void checkForCollisions() {
        if (arFragment.getArSceneView().getScene() == null || currentBird == null) {
            return;
        }

        Node node = arFragment.getArSceneView().getScene().overlapTest(currentBird);
        if (node != null) {
            score++;
            scoreTextView.setText(String.valueOf(score));
            randomlyPlace((Obstacle) node);
            currentBird.setVelocity(null);
        }
    }

    private void handleHandDetection(Canvas canvas, List<MatOfPoint> contours, Region validRegion) {
        Path drawPath = new Path();
        Path hullPath = new Path();

        Paint pathPaint = new Paint();
        pathPaint.setStyle(Paint.Style.STROKE);
        pathPaint.setStrokeWidth(5);

        Paint hullPaint = new Paint();
        hullPaint.setAntiAlias(true);
        hullPaint.setColor(Color.GREEN);
        hullPaint.setStyle(Paint.Style.STROKE);
        hullPaint.setStrokeWidth(5);

        if (contours.size() > 0) {
            // At least one contour was detected
            if (contours.size() >= 2) {
                try {
                    Collections.sort(contours, (o1, o2) -> {
                        if (o1 == null) {
                            return 1;
                        }
                        if (o2 == null) {
                            return -1;
                        }

                        Rect rect1 = Imgproc.boundingRect(o1);
                        Rect rect2 = Imgproc.boundingRect(o2);

                        if (rect1.area() < rect2.area()) {
                            return 1;
                        }
                        return -1;
                    });
                } catch (Exception e) {
                    Timber.e(e);
                }
            }

            MatOfPoint handContour = getHandContour(contours, validRegion, 0.05f, 0.5f);
            if (handContour == null) {
                // Hand was not detected.
                reset();
                return;
            }

            Point[] points = handContour.toArray();
            for (int i = 0; i < points.length; i++) {
                float x = (float) points[i].x;
                float y = (float) points[i].y;

                if (validRegion.contains((int)x, (int)y)) {
                    if (drawPath.isEmpty()) {
                        drawPath.moveTo(x, y);
                    } else {
                        drawPath.lineTo(x, y);
                    }
                }
            }
            drawPath.close();

            MatOfInt hull = new MatOfInt();
            Imgproc.convexHull(handContour, hull);

            org.opencv.core.Point[] contourArray = handContour.toArray();
            ArrayList<Point> hullPoints = new ArrayList<>();
            List<Integer> hullContourIdxList = hull.toList();
            for (int i = 0; i < hullContourIdxList.size(); i++) {
                hullPoints.add(contourArray[hullContourIdxList.get(i)]);
            }

            removeClosePoints(hullPoints, hullPoints, 50);
            removeOutliers(hullPoints, validRegion);

            MatOfInt4 convexityDefects = new MatOfInt4();
            Imgproc.convexityDefects(handContour, hull, convexityDefects);
            ArrayList<Point> convexPoints = new ArrayList<>();
            if (!convexityDefects.empty()) {
                int[] cdList = convexityDefects.toArray();
                pathPaint.setColor(Color.RED);
                for (int i = 0; i < cdList.length; i += 4) {
                    Point defect = contourArray[cdList[i + 2]];
                    convexPoints.add(defect);
                }
            }

            removeClosePoints(hullPoints, convexPoints, 50);
            removeOutliers(convexPoints, validRegion);

            float decisionAngle = 90;
            int fingerCount = 0;
            if (!convexityDefects.empty()) {
                int[] cdList = convexityDefects.toArray();
                pathPaint.setColor(Color.RED);
                for (int i = 0; i < cdList.length; i += 4) {
                    Point defect = contourArray[cdList[i + 2]];
                    if (convexPoints.contains(defect)) {
                        Point start = contourArray[cdList[i]];
                        Point end = contourArray[cdList[i] + 1];

                        double a = Math.sqrt(Math.pow(defect.x - end.x, 2) + Math.pow(defect.y - end.y, 2));
                        double b = Math.sqrt(Math.pow(defect.x - start.x, 2) + Math.pow(defect.y - start.y, 2));
                        double c = Math.sqrt(Math.pow(start.x - end.x, 2) + Math.pow(start.y - end.y, 2));

                        double angle = Math.acos((Math.pow(a, 2) + Math.pow(b, 2) - Math.pow(c, 2)) / (2 * a * b)) * (180 / Math.PI);
                        if (angle < decisionAngle) {
                            fingerCount++;
                        }
                    }
                }
            }

            // DRAWING
            hullPaint.setColor(Color.GREEN);
            for (int i = 0; i < hullPoints.size(); i++) {
                Point point = hullPoints.get(i);
                canvas.drawCircle((float) point.x, (float) point.y, 10, hullPaint);

                if (i == 0) {
                    hullPath.moveTo((float) point.x, (float) point.y);
                } else {
                    hullPath.lineTo((float) point.x, (float) point.y);
                }
            }
            if (hullPoints.size() > 0) {
                hullPath.close();
            }

            hullPaint.setColor(Color.RED);
            for (int i = 0; i < convexPoints.size(); i++) {
                Point point = convexPoints.get(i);
                canvas.drawCircle((float) point.x, (float) point.y, 10, hullPaint);
            }

            Timber.d("handleHandDetection: fingerCount = %s", fingerCount);

            if (fingerCount >= 4) {
                if (currentState != OPEN_HAND && canShoot) {
                    Timber.d("handleHandDetection: progress is 0.");
                    runOnUiThread(() -> {
                        shootDroid();
                        canShoot = false;
                    });
                }
                reset();

                currentState = STATE.OPEN_HAND;
                progressBar.setProgress(0);
            } else {

                float radius = canvas.getWidth() / 4f;
                float centerX = canvas.getWidth() / 2f;
                float centerY = canvas.getHeight() - 2 * radius;

                hullPaint.setColor(Color.YELLOW);

                Path validPath = new Path();
                validPath.addCircle(centerX, centerY, radius, Path.Direction.CCW);

                Region vRegion = new Region();

                RectF rect = new RectF();
                validPath.computeBounds(rect, true);

                vRegion.set(new android.graphics.Rect((int) rect.left, (int) rect.top, (int) rect.right, (int) rect.bottom));
                vRegion.setPath(validPath, vRegion);

                RectF bound = getBounds(hullPoints);

                Vector3 centerPoint = new Vector3(bound.centerX(), bound.centerY(), 0);
                if (distance(new Point(centerPoint.x, centerPoint.y), new Point(centerX, centerY)) > radius) {
                    Vector3 tempPoint = Vector3.subtract(centerPoint, new Vector3(centerX, centerY, 0));
                    tempPoint = tempPoint.normalized();
                    tempPoint = tempPoint.scaled(radius);
                    centerPoint = new Vector3(centerX + tempPoint.x, centerY + tempPoint.y, 0);
                }

                // Draw the dot
                hullPaint.setStyle(Paint.Style.FILL_AND_STROKE);
                canvas.drawCircle(centerPoint.x, centerPoint.y, 5, hullPaint);

                // Draw the allowed circle
                hullPaint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(centerX, centerY, radius, hullPaint);

                xRatio = (centerPoint.x - centerX) / radius;
                yRatio = (centerPoint.y - centerY) / radius;

                float area = getArea(drawPath);
                if (area < minArea - 1000) {
                    minArea = area;
                    areaList.clear();
                    ratios.clear();
                    progressBar.setProgress(0);
                } else {
                    areaList.add(area);
                    ratios.add(new Point(xRatio, yRatio));
                }


                if (currentState == STATE.OPEN_HAND) {
                    reset();
                }
                if (areaList.size() > 0) {
                    float value = Math.abs(minArea - areaList.get(areaList.size() - 1));
                    float displayPower = value / 10000;

                    int progress = Math.round(displayPower * 100);
                    int roundedToTens = ((progress+5)/10)*10;
                    progressBar.setProgress(roundedToTens);

                    // We must ignore the last 15 samples as the act of opening the hand messes up the power.
                    power = Math.min(1f, Math.abs(minArea - areaList.get(Math.max(0, areaList.size() - 15))) / 10000f);
                }
                currentState = STATE.FIST;
            }

        } else {
            // Nothing detected.
            reset();
        }

        pathPaint.setColor(Color.BLUE);
        canvas.drawPath(drawPath, pathPaint);
    }

    private boolean lessThan(float area, List<Float> points, int lastN) {
        if (points == null || points.size() == 0) {
            return false;
        }

        List<Float> sublist = points.subList(Math.max(0, points.size() - lastN), points.size());
        float count = 0;
        for (Float value : sublist) {
            if (area < value) {
                count++;
            }
        }
        return count >= lastN;
    }

    private void removeOutliers(ArrayList<Point> points, Region region) {
        for (Point point : points) {
            if (!region.contains((int)point.x, (int)point.y)) {
                point.x = 0;
                point.y = 0;
            }
        }
        ArrayList<Point> toRemove = new ArrayList<>();
        toRemove.add(new Point(0,0));
        points.removeAll(toRemove);
    }

    private void removeClosePoints(ArrayList<Point> keep, ArrayList<Point> remove, float maxDist) {
        for (int i = 0; i < keep.size(); i++) {
            Point point1 = keep.get(i);
            for (int j = 0; j < remove.size(); j++) {
                Point point2 = remove.get(j);
                if (!point1.equals(point2) && distance(point1, point2) < maxDist) {
                    point2.x = 0;
                    point2.y = 0;
                }
            }
        }
        ArrayList<Point> toRemove = new ArrayList<>();
        toRemove.add(new Point(0, 0));
        remove.removeAll(toRemove);
    }

    private MatOfPoint getHandContour(List<MatOfPoint> contours, Region validRegion, float first, float second) {
        for(MatOfPoint contour : contours) {

            RectF contourBounds = getBounds(contour);
            RectF intersection = new RectF(contourBounds);
            if (intersection.intersect(new RectF(validRegion.getBounds()))
                    && getArea(contourBounds) > getArea(validRegion.getBoundaryPath()) * first
                    && getArea(intersection) > getArea(contourBounds) * second) {
                return contour;
            }
        }
        return null;
    }

    private RectF getBounds(ArrayList<Point> points) {
        float left = Float.MAX_VALUE, top = Float.MAX_VALUE, right = Float.MIN_VALUE, bottom = Float.MIN_VALUE;
        for (Point point : points) {
            float x = (float) point.x;
            float y = (float) point.y;
            if (x < left) {
                left = x;
            }
            if (x > right) {
                right = x;
            }
            if (y < top) {
                top = y;
            }
            if (y > bottom) {
                bottom = y;
            }
        }
        return new RectF(left, top, right, bottom);
    }


    private RectF getBounds(MatOfPoint contour) {
        org.opencv.core.Point[] points = contour.toArray();

        float left = Float.MAX_VALUE, top = Float.MAX_VALUE, right = Float.MIN_VALUE, bottom = Float.MIN_VALUE;
        for (int i = 0; i < points.length; i++) {
            float x = (float) points[i].x;
            float y = (float) points[i].y;
            if (x < left) {
                left = x;
            }
            if (x > right) {
                right = x;
            }
            if (y < top) {
                top = y;
            }
            if (y > bottom) {
                bottom = y;
            }
        }
        return new RectF(left, top, right, bottom);
    }

    private float getArea(RectF rectF) {
        return rectF.width() * rectF.height();
    }

    private float getArea(Path drawPath) {
        Region region = new Region();

        RectF rect = new RectF();
        drawPath.computeBounds(rect, true);

        region.set(new android.graphics.Rect((int)rect.left, (int)rect.top, (int)rect.right, (int)rect.bottom));
        region.setPath(drawPath, region);

        RegionIterator regionIterator = new RegionIterator(region);

        float area = 0; // units of area

        android.graphics.Rect tmpRect = new android.graphics.Rect();

        while (regionIterator.next(tmpRect)) {
            area += tmpRect.width() * tmpRect.height();
        }
        return area;
    }
}