package com.example.tryd;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonArrayRequest;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.compass.CompassOverlay;
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Navigation extends AppCompatActivity {

    private MapView map;
    private FusedLocationProviderClient fusedLocationClient;
    private TextToSpeech tts;
    private double currentLat, currentLon;
    private String graphHopperApiKey = "d71eb914-da95-4dfd-9508-dae8284fde33";

    private List<InstructionStep> instructionSteps = new ArrayList<>();
    private List<GeoPoint> currentRoutePoints = new ArrayList<>();
    private int nextInstructionIndex = 0;
    private boolean isGuidanceActive = false;
    private Locale destinationLang = Locale.getDefault();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this));
        setContentView(R.layout.navigation);

        map = findViewById(R.id.map);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        CompassOverlay compass = new CompassOverlay(this, new InternalCompassOrientationProvider(this), map);
        compass.enableCompass();
        map.getOverlays().add(compass);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        getCurrentLocation();

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.getDefault());
                tts.speak("S'il vous plaît, dites votre destination", TextToSpeech.QUEUE_FLUSH, null, null);
                startVoiceInput();
            }
        });

        Button voiceBtn = findViewById(R.id.btnVoice);
        voiceBtn.setOnClickListener(v -> startVoiceInput());
    }

    private void startVoiceInput() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-TN");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Parlez maintenant (Français ou Arabe)");

        try {
            startActivityForResult(intent, 1);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Reconnaissance vocale non disponible", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            ArrayList<String> result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (result != null && !result.isEmpty()) {
                String destination = result.get(0);
                destinationLang = destination.matches(".[\\u0600-\\u06FF]+.") ? new Locale("ar") : Locale.FRENCH;
                geocodeDestination(destination);
            }
        }
    }

    private void geocodeDestination(String destination) {
        String url = "https://nominatim.openstreetmap.org/search?q=" + Uri.encode(destination) + "&format=json&limit=1";
        RequestQueue queue = Volley.newRequestQueue(this);

        JsonArrayRequest request = new JsonArrayRequest(Request.Method.GET, url, null,
                response -> {
                    try {
                        if (response.length() > 0) {
                            JSONObject obj = response.getJSONObject(0);
                            double lat = obj.getDouble("lat");
                            double lon = obj.getDouble("lon");
                            getRoute(currentLat, currentLon, lat, lon);
                        } else {
                            Toast.makeText(this, "Destination introuvable", Toast.LENGTH_SHORT).show();
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }, Throwable::printStackTrace);

        queue.add(request);
    }

    private void getRoute(double fromLat, double fromLon, double toLat, double toLon) {
        String url = "https://graphhopper.com/api/1/route?point=" + fromLat + "," + fromLon +
                "&point=" + toLat + "," + toLon +
                "&vehicle=car&locale=fr&instructions=true&points_encoded=false&key=" + graphHopperApiKey;

        JsonObjectRequest request = new JsonObjectRequest(Request.Method.GET, url, null,
                response -> {
                    try {
                        JSONArray paths = response.getJSONArray("paths");
                        if (paths.length() > 0) {
                            JSONObject path = paths.getJSONObject(0);
                            JSONArray coords = path.getJSONObject("points").getJSONArray("coordinates");

                            List<GeoPoint> geoPoints = new ArrayList<>();
                            for (int i = 0; i < coords.length(); i++) {
                                JSONArray coord = coords.getJSONArray(i);
                                geoPoints.add(new GeoPoint(coord.getDouble(1), coord.getDouble(0)));
                            }

                            drawRoute(geoPoints);
                            currentRoutePoints = geoPoints;

                            double duration = path.getDouble("time") / 60000.0;
                            tts.setLanguage(destinationLang);
                            tts.speak(destinationLang.getLanguage().equals("ar") ?
                                            "المدة المقدرة " + Math.round(duration) + " دقيقة" :
                                            "La durée estimée est de " + Math.round(duration) + " minutes",
                                    TextToSpeech.QUEUE_ADD, null, null);

                            JSONArray instructions = path.getJSONArray("instructions");
                            instructionSteps.clear();

                            for (int i = 0; i < instructions.length(); i++) {
                                JSONObject instr = instructions.getJSONObject(i);
                                int intervalStart = instr.getJSONArray("interval").getInt(0);
                                int sign = instr.getInt("sign");
                                int exit = instr.optInt("exit_number", -1);
                                String name = instr.getString("street_name");
                                GeoPoint stepPoint = geoPoints.get(intervalStart);
                                instructionSteps.add(new InstructionStep(stepPoint, generateSpokenInstruction(sign, exit, name)));
                            }

                            nextInstructionIndex = 0;
                            isGuidanceActive = true;
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }, Throwable::printStackTrace);

        Volley.newRequestQueue(this).add(request);
    }

    private String generateSpokenInstruction(int sign, int exit, String name) {
        String suffix = name.isEmpty() ? "" :
                destinationLang.getLanguage().equals("ar") ? " في " + name : " sur " + name;

        if (destinationLang.getLanguage().equals("ar")) {
            switch (sign) {
                case -3: return "انعطف بشدة إلى اليسار" + suffix;
                case -2: return "انعطف يسارا" + suffix;
                case -1: return "انعطف قليلاً إلى اليسار" + suffix;
                case 0: return "واصل السير مباشرة" + suffix;
                case 1: return "انعطف قليلاً إلى اليمين" + suffix;
                case 2: return "انعطف يمينًا" + suffix;
                case 3: return "انعطف بشدة إلى اليمين" + suffix;
                case 4: return "قم بالعودة للخلف";
                case 5: case 6: case 7: return "في الدوار، خذ المخرج رقم " + exit;
                default: return "واصل الطريق";
            }
        } else {
            switch (sign) {
                case -3: return "Tournez fort à gauche" + suffix;
                case -2: return "Tournez à gauche" + suffix;
                case -1: return "Tournez légèrement à gauche" + suffix;
                case 0: return "Continuez tout droit" + suffix;
                case 1: return "Tournez légèrement à droite" + suffix;
                case 2: return "Tournez à droite" + suffix;
                case 3: return "Tournez fort à droite" + suffix;
                case 4: return "Faites demi-tour";
                case 5: case 6: case 7: return "Au rond-point, prenez la " + exit + "ᵉ sortie";
                default: return "Continuez l’itinéraire";
            }
        }
    }

    private void drawRoute(List<GeoPoint> points) {
        Polyline polyline = new Polyline();
        polyline.setPoints(points);
        polyline.setColor(0xFF0066FF);
        polyline.setWidth(8f);
        map.getOverlays().clear();
        map.getOverlays().add(polyline);
        map.invalidate();
    }

    private void getCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            return;
        }

        LocationRequest request = LocationRequest.create();
        request.setInterval(3000);
        request.setFastestInterval(2000);
        request.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());

        fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
            if (location != null) {
                currentLat = location.getLatitude();
                currentLon = location.getLongitude();
                GeoPoint start = new GeoPoint(currentLat, currentLon);
                IMapController controller = map.getController();
                controller.setZoom(16);
                controller.setCenter(start);
            }
        });
    }

    private final LocationCallback locationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(LocationResult result) {
            if (result != null && result.getLastLocation() != null) {
                currentLat = result.getLastLocation().getLatitude();
                currentLon = result.getLastLocation().getLongitude();

                if (isGuidanceActive && nextInstructionIndex < instructionSteps.size()) {
                    InstructionStep step = instructionSteps.get(nextInstructionIndex);
                    float[] distance = new float[1];
                    Location.distanceBetween(currentLat, currentLon, step.point.getLatitude(), step.point.getLongitude(), distance);

                    if (distance[0] < 20) {
                        tts.speak(step.instruction, TextToSpeech.QUEUE_FLUSH, null, null);
                        nextInstructionIndex++;
                    } else if (distance[0] < 100 && !step.announced) {
                        String msg = destinationLang.getLanguage().equals("ar") ?
                                "تبقى " + (int) distance[0] + " متر قبل " + step.instruction :
                                "Il reste " + (int) distance[0] + " mètres avant " + step.instruction;

                        tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, null);
                        step.announced = true;
                    } else if (distance[0] > 150) {
                        tts.speak(destinationLang.getLanguage().equals("ar") ?
                                "إعادة حساب الطريق" : "il reste ", TextToSpeech.QUEUE_FLUSH, null, null);
                        isGuidanceActive = false;
                        nextInstructionIndex = 0;
                        getRoute(currentLat, currentLon, instructionSteps.get(instructionSteps.size() - 1).point.getLatitude(),
                                instructionSteps.get(instructionSteps.size() - 1).point.getLongitude());
                    }
                }
            }
        }
    };

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    private static class InstructionStep {
        GeoPoint point;
        String instruction;
        boolean announced = false;

        InstructionStep(GeoPoint p, String i) {
            point = p;
            instruction = i;
        }
    }

    private void saveRouteAsJson(String name) {
        try {
            JSONArray jsonArray = new JSONArray();
            for (GeoPoint p : currentRoutePoints) {
                JSONArray coord = new JSONArray();
                coord.put(p.getLatitude());
                coord.put(p.getLongitude());
                jsonArray.put(coord);
            }

            JSONObject obj = new JSONObject();  // Déplacé dans le try
            obj.put("name", name);
            obj.put("points", jsonArray);

            File dir = new File(getExternalFilesDir(null), "trajets");
            if (!dir.exists()) dir.mkdirs();

            File file = new File(dir, name + ".json");
            FileWriter writer = new FileWriter(file);
            writer.write(obj.toString(2));
            writer.close();

            tts.speak(destinationLang.getLanguage().equals("ar") ?
                    "تم حفظ المسار" : "Trajet enregistré", TextToSpeech.QUEUE_FLUSH, null, null);

        } catch (IOException | JSONException e) {
            e.printStackTrace();
            tts.speak("Erreur d’enregistrement", TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

}