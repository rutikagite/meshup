package com.example.meshup;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.view.GravityCompat;


import com.bumptech.glide.Glide;
import com.example.meshup.databinding.ActivityMainBinding;
import com.google.android.material.navigation.NavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;

import java.io.IOException;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    private AppBarConfiguration mAppBarConfiguration;
    private ActivityMainBinding binding;

    private static final int REQUEST_ALL_PERMISSIONS = 1001;
    private static final int REQUEST_ENABLE_BT = 1002;
    private final UUID APP_UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");
    private BluetoothAdapter bluetoothAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.appBarMain.toolbar);

        DrawerLayout drawer = binding.drawerLayout;
        NavigationView navigationView = binding.navView;

        mAppBarConfiguration = new AppBarConfiguration.Builder(
                R.id.nav_home, R.id.nav_gallery, R.id.nav_slideshow)
                .setOpenableLayout(drawer)
                .build();

        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        NavigationUI.setupActionBarWithNavController(this, navController, mAppBarConfiguration);
        NavigationUI.setupWithNavController(navigationView, navController);

        navigationView.setNavigationItemSelectedListener(this);

        loadProfileToDrawer();
        checkPermissionsAndEnableBluetooth();
    }

    private void checkPermissionsAndEnableBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.ACCESS_FINE_LOCATION
                }, REQUEST_ALL_PERMISSIONS);
            } else {
                enableBluetooth();
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION
                }, REQUEST_ALL_PERMISSIONS);
            } else {
                enableBluetooth();
            }
        }
    }

    private void enableBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Bluetooth permission not granted", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }
    }


    private void loadProfileToDrawer() {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) return;

        View headerView = binding.navView.getHeaderView(0);
        TextView nameText = headerView.findViewById(R.id.profileName);
        ImageView profileImage = headerView.findViewById(R.id.profileImage);

        FirebaseDatabase.getInstance().getReference("Users")
                .child(currentUser.getUid())
                .get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot.exists()) {
                        String name = snapshot.child("name").getValue(String.class);
                        String base64Image = snapshot.child("profileImageBase64").getValue(String.class);

                        nameText.setText(name != null ? name : "Unknown User");

                        if (base64Image != null && !base64Image.isEmpty()) {
                            byte[] imageBytes = Base64.decode(base64Image, Base64.DEFAULT);
                            Glide.with(this)
                                    .asBitmap()
                                    .load(imageBytes)
                                    .circleCrop()
                                    .into(profileImage);
                        } else {
                            profileImage.setImageResource(R.mipmap.ic_launcher_round);
                        }
                    } else {
                        Toast.makeText(this, "User profile not found", Toast.LENGTH_SHORT).show();
                    }
                })
                .addOnFailureListener(e ->
                        Toast.makeText(MainActivity.this, "Failed to load profile", Toast.LENGTH_SHORT).show());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);

        if (id == R.id.nav_home) {
            navController.navigate(R.id.nav_home);
        } else if (id == R.id.nav_slideshow) {
            logoutUser();
        } else if (id == R.id.nav_gallery) {
            navController.navigate(R.id.nav_gallery);
        }

        DrawerLayout drawer = binding.drawerLayout;
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }


    private void logoutUser() {
        FirebaseAuth.getInstance().signOut();

        SharedPreferences prefs = getSharedPreferences("MeshUpPrefs", MODE_PRIVATE);
        prefs.edit().clear().apply();

        Intent intent = new Intent(MainActivity.this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();

        Toast.makeText(MainActivity.this, "Logged out successfully", Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        return NavigationUI.navigateUp(navController, mAppBarConfiguration)
                || super.onSupportNavigateUp();
    }
}