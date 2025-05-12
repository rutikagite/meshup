package com.example.meshup.ui.logout;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.meshup.LoginActivity;
import com.example.meshup.R;
import com.example.meshup.databinding.FragmentSlideshowBinding;
import com.google.firebase.auth.FirebaseAuth;

public class LogoutFragment extends Fragment {

    private FragmentSlideshowBinding binding;
    private LogoutViewModel logoutViewModel;
    private FirebaseAuth mAuth;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        logoutViewModel =
                new ViewModelProvider(this).get(LogoutViewModel.class);

        binding = FragmentSlideshowBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        // Initialize Firebase Auth
        mAuth = FirebaseAuth.getInstance();

        // Set up logout button
        binding.logoutButton.setOnClickListener(v -> {
            // Clear Bluetooth devices
            //BluetoothDeviceManager.getInstance().clearDevices();

            // Sign out from Firebase
            mAuth.signOut();

            // Show logout message
            Toast.makeText(getContext(), "Logged Out Successfully" , Toast.LENGTH_SHORT).show();

            // Navigate to login activity
            Intent intent = new Intent(getActivity(), LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            requireActivity().finish();
        });

        return root;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}