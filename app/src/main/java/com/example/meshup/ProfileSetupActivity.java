package com.example.meshup;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class ProfileSetupActivity extends AppCompatActivity {

    private static final int PICK_IMAGE = 1001;

    EditText profileName;
    ImageView profilePic;
    Button saveProfileBtn;

    Uri selectedImageUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_setup);

        profileName = findViewById(R.id.profileNameEditText);
        profilePic = findViewById(R.id.profileImageView);
        saveProfileBtn = findViewById(R.id.saveProfileButton);

        profilePic.setOnClickListener(view -> {
            Intent pickImage = new Intent(Intent.ACTION_GET_CONTENT);
            pickImage.setType("image/*");
            startActivityForResult(pickImage, PICK_IMAGE);
        });

        saveProfileBtn.setOnClickListener(view -> {
            String name = profileName.getText().toString().trim();

            if (name.isEmpty() || selectedImageUri == null) {
                Toast.makeText(this, "Please enter name and pick a photo", Toast.LENGTH_SHORT).show();
                return;
            }

            // Convert image to Base64 and upload
            try {
                InputStream inputStream = getContentResolver().openInputStream(selectedImageUri);
                Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                byte[] imageBytes = baos.toByteArray();
                String encodedImage = Base64.encodeToString(imageBytes, Base64.DEFAULT);

                FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                if (user != null) {
                    String uid = user.getUid();

                    // Store name and Base64 string in Firebase
                    FirebaseDatabase.getInstance().getReference("Users")
                            .child(uid)
                            .setValue(new UserModel(uid, name, encodedImage))
                            .addOnSuccessListener(unused -> {
                                Toast.makeText(this, "Profile saved", Toast.LENGTH_SHORT).show();
                                startActivity(new Intent(this, MainActivity.class));
                                finish();
                            })
                            .addOnFailureListener(e ->
                                    Toast.makeText(this, "Failed to save profile", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "Image processing failed", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            selectedImageUri = data.getData();
            profilePic.setImageURI(selectedImageUri);
        }
    }
}
