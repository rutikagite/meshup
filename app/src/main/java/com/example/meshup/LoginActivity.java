package com.example.meshup;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;

public class LoginActivity extends AppCompatActivity {

    private EditText email, password;
    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        email = findViewById(R.id.email);
        password = findViewById(R.id.password);
        Button loginBtn = findViewById(R.id.loginBtn);

        mAuth = FirebaseAuth.getInstance();

        loginBtn.setOnClickListener(v -> {
            String userEmail = email.getText().toString().trim();
            String userPass = password.getText().toString().trim();

            if (TextUtils.isEmpty(userEmail) || TextUtils.isEmpty(userPass)) {
                Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show();
                return;
            }

            mAuth.signInWithEmailAndPassword(userEmail, userPass)
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            checkProfileSetup();
                        } else {
                            // Try to register
                            mAuth.createUserWithEmailAndPassword(userEmail, userPass)
                                    .addOnCompleteListener(registerTask -> {
                                        if (registerTask.isSuccessful()) {
                                            // New user – direct to Profile Setup
                                            startActivity(new Intent(LoginActivity.this, ProfileSetupActivity.class));
                                            finish();
                                        } else {
                                            Toast.makeText(this, "Login or Registration failed", Toast.LENGTH_SHORT).show();
                                        }
                                    });
                        }
                    });
        });
    }

    private void checkProfileSetup() {
        FirebaseUser user = mAuth.getCurrentUser();

        if (user == null) {
            Toast.makeText(this, "Unexpected error: user is null", Toast.LENGTH_SHORT).show();
            return;
        }

        String uid = user.getUid();

        FirebaseDatabase.getInstance().getReference("Users")
                .child(uid)
                .get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot.exists()) {
                        // Profile exists → go to main
                        startActivity(new Intent(LoginActivity.this, MainActivity.class));
                    } else {
                        // Profile missing → go to profile setup
                        startActivity(new Intent(LoginActivity.this, ProfileSetupActivity.class));
                    }
                    finish();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Failed to check profile: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }
}
