package com.google.ar.sceneform.samples.hellosceneform;

import android.util.Log;

import com.google.ar.core.Pose;
import com.google.ar.sceneform.ArSceneView;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.Vector3;

import static com.google.ar.sceneform.samples.hellosceneform.HelloSceneformActivity.xRatio;
import static com.google.ar.sceneform.samples.hellosceneform.HelloSceneformActivity.yRatio;

public class Bird extends Node {
    private static final String TAG = Bird.class.getSimpleName();

    private static final float GRAVITY = 1f;

    private Vector3 velocity;
    private ArSceneView arSceneView;
    private long timeOfShoot = 0;

    public Bird(ArSceneView sceneView) {
        this.arSceneView = sceneView;
    }

    public ArSceneView getArSceneView() {
        return arSceneView;
    }

    public void setVelocity(Vector3 velocity) {
        if(velocity != null) {
            timeOfShoot = System.currentTimeMillis();
        }
        else {
            timeOfShoot = 0;
            HelloSceneformActivity.canShoot = true;
        }

        this.velocity = velocity;
    }

    public void setTimeOfShoot(long timeOfShoot) {
        this.timeOfShoot = timeOfShoot;
    }

    @Override
    public void onUpdate(FrameTime frameTime) {

        if(System.currentTimeMillis() - timeOfShoot > 1200) {
            setVelocity(null);
        }


        if(velocity == null) {
            Pose pose = getArSceneView().getArFrame().getCamera().getPose();

            Pose poseForward = pose.compose(Pose.makeTranslation(0.2f, 0f, -1f));
            float[] forwardPoint = poseForward.getTranslation();

            setWorldPosition(new Vector3(forwardPoint[0], forwardPoint[1], forwardPoint[2]));

            Pose pose2 = getArSceneView().getArFrame().getCamera().getPose();
            float[] forwardQuar = pose2.getRotationQuaternion();
            Vector3 somewhere = Quaternion.rotateVector(new Quaternion(forwardQuar[0], forwardQuar[1], forwardQuar[2], forwardQuar[3]),
                    new Vector3(-5f + 2f * (yRatio * -1), 2f * (xRatio * -1), -5f));


            somewhere = somewhere.negated();
            Vector3 cameraPosition = somewhere;

            //Vector3 cameraPosition = getArSceneView().getScene().getCamera().getWorldPosition();
            Vector3 cardPosition = getWorldPosition();
            Vector3 direction = Vector3.subtract(cameraPosition, cardPosition);
            Quaternion lookRotation = Quaternion.lookRotation(direction, Vector3.up());

            setWorldRotation(lookRotation);

            return;
        }






        // Typically, getScene() will never ret
        // urn null because onUpdate() is only called when the node
        // is in the scene.
        // However, if onUpdate is called explicitly or if the node is removed from the scene on a
        // different thread during onUpdate, then getScene may be null.
        Log.d("Bird", "velocity = " + velocity);

        //setWorldPosition(new Vector3(getWorldPosition().x + velocity.x, getWorldPosition().y + velocity.y, getWorldPosition().z + velocity.z))
        velocity.y -= GRAVITY;
        velocity = velocity.scaled(frameTime.getDeltaSeconds());
        setWorldPosition(new Vector3(getWorldPosition().x + velocity.x, getWorldPosition().y + velocity.y, getWorldPosition().z + velocity.z));
        velocity = velocity.scaled(1 / frameTime.getDeltaSeconds());
        Log.d("Bird", "localPosition = " + getLocalPosition());
    }
}
