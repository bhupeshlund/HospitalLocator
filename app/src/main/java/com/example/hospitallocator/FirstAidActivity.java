package com.example.hospitallocator;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class FirstAidActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_first_aid);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("First Aid Instructions");
        }
    }
}