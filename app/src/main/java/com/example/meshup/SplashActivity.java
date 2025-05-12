package com.example.meshup;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;

public class SplashActivity extends AppCompatActivity {

    private static final int SPLASH_TIME_OUT = 2000; // 2 seconds

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ✅ Ensure Firebase is initialized
        FirebaseApp.initializeApp(this);

        setContentView(R.layout.activity_splash);

        new Handler().postDelayed(() -> {
            FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
            if (currentUser == null) {
                // Not logged in → go to login
                startActivity(new Intent(this, LoginActivity.class));
                finish();
            } else {
                // Logged in → check if profile exists
                FirebaseDatabase.getInstance().getReference("Users")
                        .child(currentUser.getUid())
                        .get()
                        .addOnSuccessListener(snapshot -> {
                            if (snapshot.exists()) {
                                // Profile exists → go to main
                                startActivity(new Intent(this, MainActivity.class));
                            } else {
                                // Profile not setup → go to profile setup
                                startActivity(new Intent(this, ProfileSetupActivity.class));
                            }
                            finish();
                        })
                        .addOnFailureListener(e -> {
                            startActivity(new Intent(this, LoginActivity.class));
                            finish();
                        });
            }
        }, SPLASH_TIME_OUT);

    }
}
