package net.devemperor.dictate.core;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.crashlytics.FirebaseCrashlytics;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.audio.AudioResponseFormat;
import com.openai.models.audio.transcriptions.Transcription;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;

import net.devemperor.dictate.BuildConfig;
import net.devemperor.dictate.DictateUtils;
import net.devemperor.dictate.rewording.PromptModel;
import net.devemperor.dictate.rewording.PromptsDatabaseHelper;
import net.devemperor.dictate.rewording.PromptsKeyboardAdapter;
import net.devemperor.dictate.rewording.PromptsOverviewActivity;
import net.devemperor.dictate.settings.DictateSettingsActivity;
import net.devemperor.dictate.R;
import net.devemperor.dictate.usage.UsageDatabaseHelper;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

// MAIN CLASS
public class DictateInputMethodService extends InputMethodService {

    // define handlers and runnables for background tasks
    private Handler mainHandler;
    private Handler deleteHandler;
    private Handler recordTimeHandler;
    private Runnable deleteRunnable;
    private Runnable recordTimeRunnable;

    // define variables and objects
    private long elapsedTime;
    private boolean isDeleting = false;
    private long startDeleteTime = 0;
    private int currentDeleteDelay = 50;
    private boolean isRecording = false;
    private boolean isPaused = false;
    private boolean instantPrompt = false;
    private boolean vibrationEnabled = true;
    private boolean audioFocusEnabled = true;
    private TextView selectedCharacter = null;
    private boolean spaceButtonUserHasSwiped = false;
    private int currentInputLanguagePos;
    private String currentInputLanguageValue;

    private MediaRecorder recorder;
    private ExecutorService speechApiThread;
    private ExecutorService rewordingApiThread;
    private File audioFile;
    private Vibrator vibrator;
    private SharedPreferences sp;
    private AudioManager am;
    private AudioFocusRequest audioFocusRequest;

    // define views
    private ConstraintLayout dictateKeyboardView;
    private MaterialButton settingsButton;
    private MaterialButton recordButton;
    private MaterialButton resendButton;
    private MaterialButton backspaceButton;
    private MaterialButton switchButton;
    private MaterialButton trashButton;
    private MaterialButton spaceButton;
    private MaterialButton pauseButton;
    private MaterialButton enterButton;
    private ConstraintLayout infoCl;
    private TextView infoTv;
    private Button infoYesButton;
    private Button infoNoButton;
    private ConstraintLayout promptsCl;
    private RecyclerView promptsRv;
    private TextView runningPromptTv;
    private ProgressBar runningPromptPb;
    private MaterialButton editSelectAllButton;
    private MaterialButton editUndoButton;
    private MaterialButton editRedoButton;
    private MaterialButton editCutButton;
    private MaterialButton editCopyButton;
    private MaterialButton editPasteButton;
    private LinearLayout overlayCharactersLl;
    private boolean autoSwitchBackAfterTranscription;
    private boolean removePunctuationEnabled;
    private boolean removePeriodEnabled;

    PromptsDatabaseHelper promptsDb;
    PromptsKeyboardAdapter promptsAdapter;

    UsageDatabaseHelper usageDb;

    // start method that is called when user opens the keyboard
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View onCreateInputView() {
        Context context = new ContextThemeWrapper(this, R.style.Theme_Dictate);

        // initialize some stuff
        mainHandler = new Handler(Looper.getMainLooper());
        deleteHandler = new Handler();
        recordTimeHandler = new Handler(Looper.getMainLooper());

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        sp = getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE);
        promptsDb = new PromptsDatabaseHelper(this);
        usageDb = new UsageDatabaseHelper(this);
        vibrationEnabled = sp.getBoolean("net.devemperor.dictate.vibration", true);
        currentInputLanguagePos = sp.getInt("net.devemperor.dictate.input_language_pos", 0);

        dictateKeyboardView = (ConstraintLayout) LayoutInflater.from(context).inflate(R.layout.activity_dictate_keyboard_view, null);
        ViewCompat.setOnApplyWindowInsetsListener(dictateKeyboardView, (v, insets) -> {
            v.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom);
            return insets;  // fix for overlapping with navigation bar on Android 15+
        });

        settingsButton = dictateKeyboardView.findViewById(R.id.settings_btn);
        recordButton = dictateKeyboardView.findViewById(R.id.record_btn);
        resendButton = dictateKeyboardView.findViewById(R.id.resend_btn);
        backspaceButton = dictateKeyboardView.findViewById(R.id.backspace_btn);
        switchButton = dictateKeyboardView.findViewById(R.id.switch_btn);
        trashButton = dictateKeyboardView.findViewById(R.id.trash_btn);
        spaceButton = dictateKeyboardView.findViewById(R.id.space_btn);
        pauseButton = dictateKeyboardView.findViewById(R.id.pause_btn);
        enterButton = dictateKeyboardView.findViewById(R.id.enter_btn);

        infoCl = dictateKeyboardView.findViewById(R.id.info_cl);
        infoTv = dictateKeyboardView.findViewById(R.id.info_tv);
        infoYesButton = dictateKeyboardView.findViewById(R.id.info_yes_btn);
        infoNoButton = dictateKeyboardView.findViewById(R.id.info_no_btn);

        promptsCl = dictateKeyboardView.findViewById(R.id.prompts_keyboard_cl);
        promptsRv = dictateKeyboardView.findViewById(R.id.prompts_keyboard_rv);
        runningPromptPb = dictateKeyboardView.findViewById(R.id.prompts_keyboard_running_pb);
        runningPromptTv = dictateKeyboardView.findViewById(R.id.prompts_keyboard_running_prompt_tv);

        editSelectAllButton = dictateKeyboardView.findViewById(R.id.edit_select_all_btn);
        editUndoButton = dictateKeyboardView.findViewById(R.id.edit_undo_btn);
        editRedoButton = dictateKeyboardView.findViewById(R.id.edit_redo_btn);
        editCutButton = dictateKeyboardView.findViewById(R.id.edit_cut_btn);
        editCopyButton = dictateKeyboardView.findViewById(R.id.edit_copy_btn);
        editPasteButton = dictateKeyboardView.findViewById(R.id.edit_paste_btn);

        overlayCharactersLl = dictateKeyboardView.findViewById(R.id.overlay_characters_ll);

        promptsRv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));

        // if user id is not set, set a random number as user id
        if (sp.getString("net.devemperor.dictate.user_id", "null").equals("null")) {
            sp.edit().putString("net.devemperor.dictate.user_id", String.valueOf((int) (Math.random() * 1000000))).apply();
        }

        recordTimeRunnable = new Runnable() {  // runnable to update the record button time text
            @Override
            public void run() {
                elapsedTime += 100;
                recordButton.setText(getString(R.string.dictate_send,
                        String.format(Locale.getDefault(), "%02d:%02d", (int) (elapsedTime / 60000), (int) (elapsedTime / 1000) % 60)));
                recordTimeHandler.postDelayed(this, 100);
            }
        };

        // initialize audio manager to stop and start background audio
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChange -> {
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        if (isRecording) pauseButton.performClick();
                    }
                })
                .build();

        settingsButton.setOnClickListener(v -> {
            if (isRecording) trashButton.performClick();
            infoCl.setVisibility(View.GONE);
            openSettingsActivity();
        });

        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
        recordButton.setOnClickListener(v -> {
            vibrate();

            infoCl.setVisibility(View.GONE);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                openSettingsActivity();
            } else if (!isRecording) {
                startRecording();
            } else {
                stopRecording();
            }
        });

        recordButton.setOnLongClickListener(v -> {
            vibrate();

            if (!isRecording) {  // open real settings activity to start file picker
                Intent intent = new Intent(this, DictateSettingsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra("net.devemperor.dictate.open_file_picker", true);
                startActivity(intent);
            }
            return true;
        });

        resendButton.setOnClickListener(v -> {
            vibrate();
            // if user clicked on resendButton without error before, audioFile is default audio
            if (audioFile == null) audioFile = new File(getCacheDir(), sp.getString("net.devemperor.dictate.last_file_name", "audio.mp3"));
            startWhisperApiRequest();
        });

        backspaceButton.setOnClickListener(v -> {
            vibrate();
            deleteOneCharacter();
        });

        backspaceButton.setOnLongClickListener(v -> {
            isDeleting = true;
            startDeleteTime = System.currentTimeMillis();
            currentDeleteDelay = 50;
            deleteRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isDeleting) {
                        deleteOneCharacter();
                        long diff = System.currentTimeMillis() - startDeleteTime;
                        if (diff > 1500 && currentDeleteDelay == 50) {
                            vibrate();
                            currentDeleteDelay = 25;
                        } else if (diff > 3000 && currentDeleteDelay == 25) {
                            vibrate();
                            currentDeleteDelay = 10;
                        } else if (diff > 5000 && currentDeleteDelay == 10) {
                            vibrate();
                            currentDeleteDelay = 5;
                        }
                        deleteHandler.postDelayed(this, currentDeleteDelay);
                    }
                }
            };
            deleteHandler.post(deleteRunnable);
            return true;
        });

        backspaceButton.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                isDeleting = false;
                deleteHandler.removeCallbacks(deleteRunnable);
            }
            return false;
        });

        switchButton.setOnClickListener(v -> {
            vibrate();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToPreviousInputMethod();
            }
        });

        switchButton.setOnLongClickListener(v -> {
            vibrate();

            currentInputLanguagePos++;
            recordButton.setText(getDictateButtonText());
            return true;
        });

        // trash button to abort the recording and reset all variables and views
        trashButton.setOnClickListener(v -> {
            vibrate();
            if (recorder != null) {
                recorder.stop();
                recorder.release();
                recorder = null;

                if (recordTimeRunnable != null) {
                    recordTimeHandler.removeCallbacks(recordTimeRunnable);
                }
            }
            if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);

            isRecording = false;
            isPaused = false;
            instantPrompt = false;
            recordButton.setText(R.string.dictate_record);
            recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
            recordButton.setEnabled(true);
            pauseButton.setVisibility(View.GONE);
            pauseButton.setForeground(AppCompatResources.getDrawable(context, R.drawable.ic_baseline_pause_24));
            trashButton.setVisibility(View.GONE);
        });

        // space button that changes cursor position if user swipes over it
        spaceButton.setOnTouchListener((v, event) -> {
            InputConnection inputConnection = getCurrentInputConnection();
            if (inputConnection != null) {
                spaceButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_keyboard_double_arrow_left_24,
                        0, R.drawable.ic_baseline_keyboard_double_arrow_right_24, 0);
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        spaceButtonUserHasSwiped = false;
                        spaceButton.setTag(event.getX());
                        break;

                    case MotionEvent.ACTION_MOVE:
                        float x = (float) spaceButton.getTag();
                        if (event.getX() - x > 30) {
                            inputConnection.commitText("", 2);
                            spaceButton.setTag(event.getX());
                            spaceButtonUserHasSwiped = true;
                        } else if (x - event.getX() > 30) {
                            inputConnection.commitText("", -1);
                            spaceButton.setTag(event.getX());
                            spaceButtonUserHasSwiped = true;
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                        if (!spaceButtonUserHasSwiped) {
                            vibrate();
                            inputConnection.commitText(" ", 1);
                        }
                        spaceButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
                        break;
                }
            } else {
                spaceButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
            }
            return false;
        });

        pauseButton.setOnClickListener(v -> {
            vibrate();
            if (recorder != null) {
                if (isPaused) {
                    if (audioFocusEnabled) am.requestAudioFocus(audioFocusRequest);
                    recorder.resume();
                    recordTimeHandler.post(recordTimeRunnable);
                    pauseButton.setForeground(AppCompatResources.getDrawable(context, R.drawable.ic_baseline_pause_24));
                    isPaused = false;
                } else {
                    if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);
                    recorder.pause();
                    recordTimeHandler.removeCallbacks(recordTimeRunnable);
                    pauseButton.setForeground(AppCompatResources.getDrawable(context, R.drawable.ic_baseline_mic_24));
                    isPaused = true;
                }
            }
        });

        enterButton.setOnClickListener(v -> {
            vibrate();

            InputConnection inputConnection = getCurrentInputConnection();
            if (inputConnection != null) {
                EditorInfo editorInfo = getCurrentInputEditorInfo();
                if ((editorInfo.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) {
                    inputConnection.commitText("\n", 1);
                } else {
                    inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                    inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                }
            }
        });

        enterButton.setOnLongClickListener(v -> {
            vibrate();
            overlayCharactersLl.setVisibility(View.VISIBLE);
            return true;
        });

        enterButton.setOnTouchListener((v, event) -> {
            if (overlayCharactersLl.getVisibility() == View.VISIBLE) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_MOVE:
                        for (int i = 0; i < overlayCharactersLl.getChildCount(); i++) {
                            TextView charView = (TextView) overlayCharactersLl.getChildAt(i);
                            if (isPointInsideView(event.getRawX(), charView)) {
                                if (selectedCharacter != charView) {
                                    selectedCharacter = charView;
                                    highlightSelectedCharacter(selectedCharacter);
                                }
                                break;
                            }
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        if (selectedCharacter != null) {
                            InputConnection inputConnection = getCurrentInputConnection();
                            if (inputConnection != null) {
                                inputConnection.commitText(selectedCharacter.getText(), 1);
                            }
                            selectedCharacter.setBackground(AppCompatResources.getDrawable(this, R.drawable.border_textview));
                            selectedCharacter = null;
                        }
                        overlayCharactersLl.setVisibility(View.GONE);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        overlayCharactersLl.setVisibility(View.GONE);
                        return true;
                }
            }
            return false;
        });

        editSelectAllButton.setOnClickListener(v -> {
            vibrate();

            InputConnection inputConnection = getCurrentInputConnection();
            if (inputConnection != null) {
                ExtractedText extractedText = inputConnection.getExtractedText(new ExtractedTextRequest(), 0);

                if (inputConnection.getSelectedText(0) == null && extractedText.text.length() > 0) {
                    inputConnection.performContextMenuAction(android.R.id.selectAll);
                    editSelectAllButton.setForeground(AppCompatResources.getDrawable(context, R.drawable.ic_baseline_deselect_24));
                } else {
                    inputConnection.clearMetaKeyStates(0);
                    if (extractedText == null || extractedText.text == null) {
                        inputConnection.setSelection(0, 0);
                    } else {
                        inputConnection.setSelection(extractedText.text.length(), extractedText.text.length());
                    }
                    editSelectAllButton.setForeground(AppCompatResources.getDrawable(context, R.drawable.ic_baseline_select_all_24));
                }
            }
        });

        // initialize all edit buttons
        Object[][] buttonsActions = {
                { editUndoButton, android.R.id.undo },
                { editRedoButton, android.R.id.redo },
                { editCutButton,  android.R.id.cut },
                { editCopyButton, android.R.id.copy },
                { editPasteButton, android.R.id.paste }
        };

        for (Object[] pair : buttonsActions) {
            ((Button) pair[0]).setOnClickListener(v -> {
                vibrate();
                InputConnection inputConnection = getCurrentInputConnection();
                if (inputConnection != null) {
                    inputConnection.performContextMenuAction((int) pair[1]);
                }
            });
        }

        // initialize overlay characters
        for (int i = 0; i < 8; i++) {
            TextView charView = (TextView) LayoutInflater.from(context).inflate(R.layout.item_overlay_characters, overlayCharactersLl, false);
            overlayCharactersLl.addView(charView);
        }

        return dictateKeyboardView;
    }

    // method is called if the user closed the keyboard
    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);

        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) { }
            recorder.release();
            recorder = null;

            if (recordTimeRunnable != null) {
                recordTimeHandler.removeCallbacks(recordTimeRunnable);
            }
        }

        if (speechApiThread != null) speechApiThread.shutdownNow();
        if (rewordingApiThread != null) rewordingApiThread.shutdownNow();

        pauseButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_pause_24));
        pauseButton.setVisibility(View.GONE);
        trashButton.setVisibility(View.GONE);
        resendButton.setVisibility(View.GONE);
        infoCl.setVisibility(View.GONE);
        isRecording = false;
        isPaused = false;
        instantPrompt = false;
        if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);
        recordButton.setText(R.string.dictate_record);
        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
        recordButton.setEnabled(true);
    }

    // method is called if the keyboard appears again
    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);

        if (sp.getBoolean("net.devemperor.dictate.rewording_enabled", true)) {
            promptsCl.setVisibility(View.VISIBLE);

            // collect all prompts from database
            List<PromptModel> data;
            InputConnection inputConnection = getCurrentInputConnection();
            if (inputConnection != null && inputConnection.getSelectedText(0) == null) {
                data = promptsDb.getAll(false);
                editSelectAllButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_select_all_24));
            } else {
                data = promptsDb.getAll(true);
                editSelectAllButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_deselect_24));
            }

            promptsAdapter = new PromptsKeyboardAdapter(data, position -> {
                vibrate();
                PromptModel model = data.get(position);

                if (model.getId() == -1) {  // instant prompt clicked
                    instantPrompt = true;
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        openSettingsActivity();
                    } else if (!isRecording) {
                        startRecording();
                    } else {
                        stopRecording();
                    }
                } else if (model.getId() == -2) {  // add prompt clicked
                    Intent intent = new Intent(this, PromptsOverviewActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } else {
                    startGPTApiRequest(model);  // another normal prompt clicked
                }
            });
            promptsRv.setAdapter(promptsAdapter);
        } else {
            promptsCl.setVisibility(View.GONE);
        }

        // enable resend button if previous audio file still exists in cache
        if (new File(getCacheDir(), sp.getString("net.devemperor.dictate.last_file_name", "audio.mp3")).exists()
                && sp.getBoolean("net.devemperor.dictate.resend_button", false)) {
            resendButton.setVisibility(View.VISIBLE);
        } else {
            resendButton.setVisibility(View.GONE);
        }

        // fill all overlay characters
        String charactersString = sp.getString("net.devemperor.dictate.overlay_characters", "()-:!?,.");
        for (int i = 0; i < overlayCharactersLl.getChildCount(); i++) {
            TextView charView = (TextView) overlayCharactersLl.getChildAt(i);
            if (i >= charactersString.length()) {
                charView.setVisibility(View.GONE);
            } else {
                charView.setVisibility(View.VISIBLE);
                charView.setText(charactersString.substring(i, i + 1));
            }
        }

        // get the currently selected input language
        recordButton.setText(getDictateButtonText());

        // check if user enabled audio focus
        audioFocusEnabled = sp.getBoolean("net.devemperor.dictate.audio_focus", true);

        // show infos for updates, ratings or donations
        long totalAudioTime = usageDb.getTotalAudioTime();
        if (sp.getInt("net.devemperor.dictate.last_version_code", 0) < BuildConfig.VERSION_CODE) {
            showInfo("update");
        } else if (totalAudioTime > 180 && totalAudioTime <= 600 && !sp.getBoolean("net.devemperor.dictate.flag_has_rated_in_playstore", false)) {
            showInfo("rate");  // in case someone had Dictate installed before, he shouldn't get both messages
        } else if (totalAudioTime > 600 && !sp.getBoolean("net.devemperor.dictate.flag_has_donated", false)) {
            showInfo("donate");
        }

        // start audio file transcription if user selected an audio file
        if (!sp.getString("net.devemperor.dictate.transcription_audio_file", "").isEmpty()) {
            audioFile = new File(getCacheDir(), sp.getString("net.devemperor.dictate.transcription_audio_file", ""));
            sp.edit().putString("net.devemperor.dictate.last_file_name", audioFile.getName()).apply();

            sp.edit().remove("net.devemperor.dictate.transcription_audio_file").apply();
            startWhisperApiRequest();

        } else if (sp.getBoolean("net.devemperor.dictate.instant_recording", false)) {
            recordButton.performClick();
        }

        removePunctuationEnabled = sp.getBoolean("net.devemperor.dictate.remove_punctuation", false);
        removePeriodEnabled = sp.getBoolean("net.devemperor.dictate.remove_period", false);
        autoSwitchBackAfterTranscription = sp.getBoolean("net.devemperor.dictate.switch_back_after_transcription", false);
    }

    // method is called if user changed text selection
    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onUpdateSelection (int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);

        // refill all prompts
        if (sp != null && sp.getBoolean("net.devemperor.dictate.rewording_enabled", true)) {
            List<PromptModel> data;
            if (getCurrentInputConnection().getSelectedText(0) == null) {
                data = promptsDb.getAll(false);
                editSelectAllButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_select_all_24));
            } else {
                data = promptsDb.getAll(true);
                editSelectAllButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_deselect_24));
            }

            promptsAdapter.getData().clear();
            promptsAdapter.getData().addAll(data);
            promptsAdapter.notifyDataSetChanged();
        }
    }

    private void vibrate() {
        if (vibrationEnabled) if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    private void openSettingsActivity() {
        Intent intent = new Intent(this, DictateSettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void startRecording() {
        audioFile = new File(getCacheDir(), "audio.mp3");
        sp.edit().putString("net.devemperor.dictate.last_file_name", audioFile.getName()).apply();

        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioEncodingBitRate(64000);
        recorder.setAudioSamplingRate(44100);
        recorder.setOutputFile(audioFile);

        if (audioFocusEnabled) am.requestAudioFocus(audioFocusRequest);

        try {
            recorder.prepare();
            recorder.start();
        } catch (IOException e) {
            sendLogToCrashlytics(e);
        }

        recordButton.setText(R.string.dictate_send);
        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_send_20, 0, 0, 0);
        pauseButton.setVisibility(View.VISIBLE);
        trashButton.setVisibility(View.VISIBLE);
        resendButton.setVisibility(View.GONE);
        isRecording = true;

        elapsedTime = 0;
        recordTimeHandler.post(recordTimeRunnable);
    }

    private void stopRecording() {
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) { }
            recorder.release();
            recorder = null;

            if (recordTimeRunnable != null) {
                recordTimeHandler.removeCallbacks(recordTimeRunnable);
            }

            startWhisperApiRequest();
        }
    }

    private void startWhisperApiRequest() {
        recordButton.setText(R.string.dictate_sending);
        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_send_20, 0, 0, 0);
        recordButton.setEnabled(false);
        pauseButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_pause_24));
        pauseButton.setVisibility(View.GONE);
        trashButton.setVisibility(View.GONE);
        resendButton.setVisibility(View.GONE);
        infoCl.setVisibility(View.GONE);
        isRecording = false;
        isPaused = false;

        if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);

        String stylePrompt;
        switch (sp.getInt("net.devemperor.dictate.style_prompt_selection", 1)) {
            case 1:
                stylePrompt = DictateUtils.PROMPT_PUNCTUATION_CAPITALIZATION;
                break;
            case 2:
                stylePrompt = sp.getString("net.devemperor.dictate.style_prompt_custom_text", "");
                break;
            default:
                stylePrompt = "";
        }

        String customApiHost = sp.getString("net.devemperor.dictate.custom_api_host", getString(R.string.dictate_custom_host_hint));
        String apiKey = sp.getString("net.devemperor.dictate.api_key", "NO_API_KEY").replaceAll("[^ -~]", "");
        String transcriptionModel = sp.getString("net.devemperor.dictate.transcription_model", "FunAudioLLM/SenseVoiceSmall");
        String proxyHost = sp.getString("net.devemperor.dictate.proxy_host", getString(R.string.dictate_settings_proxy_hint));

        speechApiThread = Executors.newSingleThreadExecutor();
        if (transcriptionModel.equals("FunAudioLLM/SenseVoiceSmall")) {
            speechApiThread.execute(() -> {
                try {
                    // 1. 获取 API 令牌和音频文件
                    String siliconFlowToken = sp.getString("net.devemperor.dictate.siliconflow_api_key", "DEIN_SILICONFLOW_TOKEN_FALLBACK"); // 从 SharedPreferences 中获取密钥
                    // audioFile 已经存在

                    // 2. 创建 OkHttpClient 实例
                    OkHttpClient client = new OkHttpClient.Builder()
                            .connectTimeout(5, TimeUnit.SECONDS) // 减少超时时间
                            .readTimeout(5, TimeUnit.SECONDS)
                            .writeTimeout(5, TimeUnit.SECONDS)
                            .proxy(sp.getBoolean("net.devemperor.dictate.proxy_enabled", false) ?
                                    new Proxy(Proxy.Type.HTTP, new InetSocketAddress(
                                            sp.getString("net.devemperor.dictate.proxy_host", getString(R.string.dictate_settings_proxy_hint)).split(":")[0],
                                            Integer.parseInt(sp.getString("net.devemperor.dictate.proxy_host", getString(R.string.dictate_settings_proxy_hint)).split(":")[1])))
                                    : null) // 保持代理支持
                            .build();

                    // 3. 创建 Multipart-Request-Body
                    RequestBody requestBody = new MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("file", audioFile.getName(), // 文件名
                                    RequestBody.create(audioFile, MediaType.parse("audio/mp3"))) // 文件内容和媒体类型（如果需要，调整）
                            .addFormDataPart("model", "FunAudioLLM/SenseVoiceSmall") // 作为表单字段的模型
                            // 如果 SiliconFlow 支持其他参数，如语言，请在此处添加：
                            // .addFormDataPart("language", currentInputLanguageValue) // 示例
                            .build();

                    // 4. 创建 Request
                    Request request = new Request.Builder()
                            .url("https://api.siliconflow.cn/v1/audio/transcriptions")
                            .header("Authorization", "Bearer " + siliconFlowToken)
                            // OkHttp 自动为 MultipartBody 设置正确的 Content-Type。
                            .post(requestBody)
                            .build();

                    // 5. 执行 Request 并处理响应
                    String resultText = "";
                    try (okhttp3.Response response = client.newCall(request).execute()) {
                        if (!response.isSuccessful()) {
                            String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                            Log.e("SiliconFlowAPI", "Error: " + response.code() + " " + errorBody);
                            // 在此处调整/使用现有的错误处理
                            if (vibrationEnabled)
                                vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
                            mainHandler.post(() -> {
                                resendButton.setVisibility(View.VISIBLE);
                                // 根据 response.code() 或 errorBody 提供更具体的错误消息
                                if (errorBody.toLowerCase().contains("authentication") || response.code() == 401) {
                                    showInfo("invalid_api_key"); // 或针对 SiliconFlow 的新信息消息
                                } else if (errorBody.toLowerCase().contains("quota") || response.code() == 429) {
                                    showInfo("quota_exceeded"); // 或新信息消息
                                } else {
                                    showInfo("internet_error");
                                }
                            });
                            return; // 重要：在此处结束线程
                        }

                        String responseBodyString = response.body() != null ? response.body().string() : "";
                        Log.d("SiliconFlowAPI", "Response: " + responseBodyString);

                        // 6. 解析 JSON 响应 (假设: {"text": "转录的文本"})
                        // 你需要一个 JSON 解析器。 Android 内置了 org.json。
                        try {
                            org.json.JSONObject jsonResponse = new org.json.JSONObject(responseBodyString);
                            // 文本的 Key 可能不同，请参阅 SiliconFlow 文档!
                            if (jsonResponse.has("text")) { // 确保 Key 存在
                                resultText = jsonResponse.getString("text");
                            } else if (jsonResponse.has("result")) { // 备用 Key，以防 SiliconFlow 响应不同
                                resultText = jsonResponse.getString("result");
                            } else {
                                // 如果响应结构未知或缺少文本
                                Log.e("SiliconFlowAPI", "Transcribed text not found in response: " + responseBodyString);
                                // 错误处理...
                            }
                        } catch (org.json.JSONException e) {
                            Log.e("SiliconFlowAPI", "JSON parsing error", e);
                            sendLogToCrashlytics(new RuntimeException("SiliconFlow JSONException", e));
                            // 错误处理...
                            return;
                        }
                    }

                    // 调整成本计算（或删除，如果无关紧要）
                    // usageDb.edit("FunAudioLLM/SenseVoiceSmall", DictateUtils.getAudioDuration(audioFile), 0, 0); // 调整模型名称

                    if (removePunctuationEnabled) {
                        resultText = DictateUtils.removePunctuation(resultText);
                    }

                    if (removePeriodEnabled) {
                        resultText = DictateUtils.removePeriod(resultText);
                    }

                    // 其余的插入文本等逻辑保持相似
                    if (!instantPrompt && !resultText.isEmpty()) {
                        InputConnection inputConnection = getCurrentInputConnection();
                        if (inputConnection != null) {
                            if (sp.getBoolean("net.devemperor.dictate.instant_output", false)) {
                                inputConnection.commitText(resultText, 1);
                            } else {
                                int speed = sp.getInt("net.devemperor.dictate.output_speed", 5);
                                for (int i = 0; i < resultText.length(); i++) {
                                    char character = resultText.charAt(i);
                                    mainHandler.postDelayed(() -> inputConnection.commitText(String.valueOf(character), 1), (long) (i * (20L / (speed / 5f))));
                                }
                            }
                        }
                    } else if (instantPrompt && !resultText.isEmpty()) {
                        // 继续 ChatGPT API 请求（如果此功能要保留）
                        instantPrompt = false;
                        startGPTApiRequest(new PromptModel(-1, Integer.MIN_VALUE, "", resultText, false));
                    }
                    // ... (其他现有逻辑，如 resendButton 可见性) ...
                    if (new File(getCacheDir(), sp.getString("net.devemperor.dictate.last_file_name", "audio.mp3")).exists()
                            && sp.getBoolean("net.devemperor.dictate.resend_button", false)) {
                        mainHandler.post(() -> resendButton.setVisibility(View.VISIBLE));
                    }

                } catch (IOException e) { // IOException 来自 client.newCall().execute()
                    if (!(e instanceof java.io.InterruptedIOException)) {
                        sendLogToCrashlytics(new RuntimeException("SiliconFlow API IOException", e));
                        if (vibrationEnabled)
                            vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
                        mainHandler.post(() -> {
                            resendButton.setVisibility(View.VISIBLE);
                            if (e.getMessage() != null && (e.getMessage().toLowerCase().contains("timeout") || e.getMessage().toLowerCase().contains("failed to connect"))) {
                                showInfo("timeout");
                            } else {
                                showInfo("internet_error");
                            }
                        });
                    }
                } catch (Exception e) { // 通用 Exception 处理
                    sendLogToCrashlytics(new RuntimeException("SiliconFlow API General Exception", e));
                    Log.e("SiliconFlowAPI", "General exception", e); // 添加日志记录
                    mainHandler.post(() -> {
                        recordButton.setText(getDictateButtonText());
                        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
                        recordButton.setEnabled(true);
                        resendButton.setVisibility(View.VISIBLE); // 确保重发按钮可见
                        showInfo("internet_error");
                    });
                } finally {
                    mainHandler.post(() -> {
                        recordButton.setText(getDictateButtonText()); // 或通用文本
                        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
                        recordButton.setEnabled(true);
                        if (!instantPrompt && autoSwitchBackAfterTranscription && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            switchToPreviousInputMethod();
                        }
                    });
                }
            });
        }else{
            speechApiThread.execute(() -> {
                try {
                    OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                            .apiKey(apiKey)
                            .baseUrl(sp.getBoolean("net.devemperor.dictate.custom_api_host_enabled", false) ? customApiHost : "https://api.openai.com/v1/")
                            .timeout(Duration.ofSeconds(120));

                    TranscriptionCreateParams.Builder transcriptionBuilder = TranscriptionCreateParams.builder()
                            .file(audioFile.toPath())
                            .model(transcriptionModel)
                            .responseFormat(AudioResponseFormat.JSON);  // gpt-4o-transcribe only supports json

                    if (!currentInputLanguageValue.equals("detect")) transcriptionBuilder.language(currentInputLanguageValue);
                    if (!stylePrompt.isEmpty()) transcriptionBuilder.prompt(stylePrompt);
                    if (sp.getBoolean("net.devemperor.dictate.proxy_enabled", false)) {
                        clientBuilder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost.split(":")[0], Integer.parseInt(proxyHost.split(":")[1]))));
                    }

                    Transcription transcription = clientBuilder.build().audio().transcriptions().create(transcriptionBuilder.build()).asTranscription();
                    String resultText = transcription.text();

                    if (removePunctuationEnabled) {
                        resultText = DictateUtils.removePunctuation(resultText);
                    }

                    if (removePeriodEnabled) {
                        resultText = DictateUtils.removePeriod(resultText);
                    }

                    usageDb.edit(transcriptionModel, DictateUtils.getAudioDuration(audioFile), 0, 0);

                    if (!instantPrompt) {
                        InputConnection inputConnection = getCurrentInputConnection();
                        if (inputConnection != null) {
                            if (sp.getBoolean("net.devemperor.dictate.instant_output", false)) {
                                inputConnection.commitText(resultText, 1);
                            } else {
                                int speed = sp.getInt("net.devemperor.dictate.output_speed", 5);
                                for (int i = 0; i < resultText.length(); i++) {
                                    char character = resultText.charAt(i);
                                    mainHandler.postDelayed(() -> inputConnection.commitText(String.valueOf(character), 1), (long) (i * (20L / (speed / 5f))));
                                }
                            }
                        }
                    } else {
                        // continue with ChatGPT API request
                        instantPrompt = false;
                        startGPTApiRequest(new PromptModel(-1, Integer.MIN_VALUE, "", resultText, false));
                    }

                    if (new File(getCacheDir(), sp.getString("net.devemperor.dictate.last_file_name", "audio.mp3")).exists()
                            && sp.getBoolean("net.devemperor.dictate.resend_button", false)) {
                        mainHandler.post(() -> resendButton.setVisibility(View.VISIBLE));
                    }

                } catch (RuntimeException e) {
                    if (!(e.getCause() instanceof InterruptedIOException)) {
                        sendLogToCrashlytics(e);
                        if (vibrationEnabled) vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
                        mainHandler.post(() -> {
                            resendButton.setVisibility(View.VISIBLE);
                            if (Objects.requireNonNull(e.getMessage()).contains("API key")) {
                                showInfo("invalid_api_key");
                            } else if (e.getMessage().contains("quota")) {
                                showInfo("quota_exceeded");
                            } else if (e.getMessage().contains("audio duration") || e.getMessage().contains("content size limit")) {  // gpt-o-transcribe and whisper have different limits
                                showInfo("content_size_limit");
                            } else if (e.getMessage().contains("format")) {
                                showInfo("format_not_supported");
                            } else {
                                showInfo("internet_error");
                            }
                        });
                    } else if (e.getCause().getMessage() != null && (e.getCause().getMessage().contains("timeout") || e.getCause().getMessage().contains("failed to connect"))) {
                        sendLogToCrashlytics(e);
                        if (vibrationEnabled) vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
                        mainHandler.post(() -> {
                            resendButton.setVisibility(View.VISIBLE);
                            showInfo("timeout");
                        });
                    }
                }


                mainHandler.post(() -> {
                    recordButton.setText(getDictateButtonText());
                    recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
                    recordButton.setEnabled(true);
                    if (!instantPrompt && autoSwitchBackAfterTranscription && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        switchToPreviousInputMethod();
                    }
                });
            });
        }

    }

    private void startGPTApiRequest(PromptModel model) {
        mainHandler.post(() -> {
            promptsRv.setVisibility(View.GONE);
            runningPromptTv.setVisibility(View.VISIBLE);
            runningPromptTv.setText(model.getId() == -1 ? getString(R.string.dictate_live_prompt) : model.getName());
            runningPromptPb.setVisibility(View.VISIBLE);
            infoCl.setVisibility(View.GONE);
        });

        rewordingApiThread = Executors.newSingleThreadExecutor();
        rewordingApiThread.execute(() -> {
            try {
                OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                        .apiKey(sp.getString("net.devemperor.dictate.api_key", "NO_API_KEY").replaceAll("[^ -~]", ""))
                        .timeout(Duration.ofSeconds(120));

                String proxyHost = sp.getString("net.devemperor.dictate.proxy_host", getString(R.string.dictate_settings_proxy_hint));
                if (sp.getBoolean("net.devemperor.dictate.proxy_enabled", false)) {
                    clientBuilder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost.split(":")[0], Integer.parseInt(proxyHost.split(":")[1]))));
                }

                String prompt = model.getPrompt();
                String rewordedText;
                if (prompt.startsWith("[") && prompt.endsWith("]")) {
                    rewordedText = prompt.substring(1, prompt.length() - 1);
                } else {
                    prompt += "\n\n" + DictateUtils.PROMPT_REWORDING_BE_PRECISE;
                    if (getCurrentInputConnection().getSelectedText(0) != null) {
                        prompt += "\n\n" + getCurrentInputConnection().getSelectedText(0).toString();
                    }

                    String gptModel = sp.getString("net.devemperor.dictate.rewording_model", "gpt-4o-mini");
                    ResponseCreateParams responseCreateParams = ResponseCreateParams.builder()
                            .input(prompt)
                            .model(gptModel)
                            .build();
                    Response response = clientBuilder.build().responses().create(responseCreateParams);
                    rewordedText = response.output().get(0).asMessage().content().get(0).asOutputText().text();

                    response.usage().ifPresent(usage -> usageDb.edit(gptModel, 0, usage.inputTokens(), usage.outputTokens()));
                }

                InputConnection inputConnection = getCurrentInputConnection();
                if (inputConnection != null) {
                    if (sp.getBoolean("net.devemperor.dictate.instant_output", false)) {
                        inputConnection.commitText(rewordedText, 1);
                    } else {
                        int speed = sp.getInt("net.devemperor.dictate.output_speed", 5);
                        for (int i = 0; i < rewordedText.length(); i++) {
                            char character = rewordedText.charAt(i);
                            mainHandler.postDelayed(() -> inputConnection.commitText(String.valueOf(character), 1), (long) (i * (20L / (speed / 5f))));
                        }
                    }
                }
            } catch (RuntimeException e) {
                if (!(e.getCause() instanceof InterruptedIOException)) {
                    sendLogToCrashlytics(e);
                    if (vibrationEnabled) vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
                    mainHandler.post(() -> {
                        resendButton.setVisibility(View.VISIBLE);
                        if (Objects.requireNonNull(e.getMessage()).contains("API key")) {
                            showInfo("invalid_api_key");
                        } else if (e.getMessage().contains("quota")) {
                            showInfo("quota_exceeded");
                        } else {
                            showInfo("internet_error");
                        }
                    });
                } else if (e.getCause().getMessage() != null && e.getCause().getMessage().contains("timeout")) {
                    sendLogToCrashlytics(e);
                    if (vibrationEnabled) vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
                    mainHandler.post(() -> {
                        resendButton.setVisibility(View.VISIBLE);
                        showInfo("timeout");
                    });
                }
            }

            mainHandler.post(() -> {
                promptsRv.setVisibility(View.VISIBLE);
                runningPromptTv.setVisibility(View.GONE);
                runningPromptPb.setVisibility(View.GONE);
                if (autoSwitchBackAfterTranscription && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    switchToPreviousInputMethod();
                }
            });
        });
    }

    private void sendLogToCrashlytics(Exception e) {
        // get all values from SharedPreferences and add them as custom keys to crashlytics
        FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
        for (String key : sp.getAll().keySet()) {
            Object value = sp.getAll().get(key);
            if (value instanceof Boolean) {
                crashlytics.setCustomKey(key, (Boolean) value);
            } else if (value instanceof Float) {
                crashlytics.setCustomKey(key, (Float) value);
            } else if (value instanceof Integer) {
                crashlytics.setCustomKey(key, (Integer) value);
            } else if (value instanceof Long) {
                crashlytics.setCustomKey(key, (Long) value);
            } else if (value instanceof String) {
                crashlytics.setCustomKey(key, (String) value);
            }
        }
        crashlytics.setUserId(sp.getString("net.devemperor.dictate.user_id", "null"));
        crashlytics.recordException(e);
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        Log.e("DictateInputMethodService", sw.toString());
        Log.e("DictateInputMethodService", "Recorded crashlytics report");
    }

    private void showInfo(String type) {
        infoCl.setVisibility(View.VISIBLE);
        infoNoButton.setVisibility(View.VISIBLE);
        infoTv.setTextColor(getResources().getColor(R.color.dictate_red, getTheme()));
        switch (type) {
            case "update":
                infoTv.setTextColor(getResources().getColor(R.color.dictate_blue, getTheme()));
                infoTv.setText(R.string.dictate_update_installed_msg);
                infoYesButton.setVisibility(View.VISIBLE);
                infoYesButton.setOnClickListener(v -> {
                    openSettingsActivity();
                    infoCl.setVisibility(View.GONE);
                });
                infoNoButton.setOnClickListener(v -> {
                    sp.edit().putInt("net.devemperor.dictate.last_version_code", BuildConfig.VERSION_CODE).apply();
                    infoCl.setVisibility(View.GONE);
                });
                break;
            case "rate":
                infoTv.setTextColor(getResources().getColor(R.color.dictate_blue, getTheme()));
                infoTv.setText(R.string.dictate_rate_app_msg);
                infoYesButton.setVisibility(View.VISIBLE);
                infoYesButton.setOnClickListener(v -> {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=net.devemperor.dictate"));
                    browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(browserIntent);
                    sp.edit().putBoolean("net.devemperor.dictate.flag_has_rated_in_playstore", true).apply();
                    infoCl.setVisibility(View.GONE);
                });
                infoNoButton.setOnClickListener(v -> {
                    sp.edit().putBoolean("net.devemperor.dictate.flag_has_rated_in_playstore", true).apply();
                    infoCl.setVisibility(View.GONE);
                });
                break;
            case "donate":
                infoTv.setTextColor(getResources().getColor(R.color.dictate_blue, getTheme()));
                infoTv.setText(R.string.dictate_donate_msg);
                infoYesButton.setVisibility(View.VISIBLE);
                infoYesButton.setOnClickListener(v -> {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://paypal.me/DevEmperor"));
                    browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(browserIntent);
                    sp.edit().putBoolean("net.devemperor.dictate.flag_has_donated", true)  // in case someone had Dictate installed before, he shouldn't get both messages
                            .putBoolean("net.devemperor.dictate.flag_has_rated_in_playstore", true).apply();
                    infoCl.setVisibility(View.GONE);
                });
                infoNoButton.setOnClickListener(v -> {
                    sp.edit().putBoolean("net.devemperor.dictate.flag_has_donated", true)
                            .putBoolean("net.devemperor.dictate.flag_has_rated_in_playstore", true).apply();
                    infoCl.setVisibility(View.GONE);
                });
                break;
            case "timeout":
                infoTv.setText(R.string.dictate_timeout_msg);
                infoYesButton.setVisibility(View.GONE);
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
            case "invalid_api_key":
                infoTv.setText(R.string.dictate_invalid_api_key_msg);
                infoYesButton.setVisibility(View.VISIBLE);
                infoYesButton.setOnClickListener(v -> {
                    openSettingsActivity();
                    infoCl.setVisibility(View.GONE);
                });
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
            case "quota_exceeded":
                infoTv.setText(R.string.dictate_quota_exceeded_msg);
                infoYesButton.setVisibility(View.VISIBLE);
                infoYesButton.setOnClickListener(v -> {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.openai.com/settings/organization/billing/overview"));
                    browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(browserIntent);
                    infoCl.setVisibility(View.GONE);
                });
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
            case "content_size_limit":
                infoTv.setText(R.string.dictate_content_size_limit_msg);
                infoYesButton.setVisibility(View.GONE);
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
            case "format_not_supported":
                infoTv.setText(R.string.dictate_format_not_supported_msg);
                infoYesButton.setVisibility(View.GONE);
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
            case "internet_error":
                infoTv.setText(R.string.dictate_internet_error_msg);
                infoYesButton.setVisibility(View.GONE);
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
        }
    }

    private String getDictateButtonText() {
        Set<String> currentInputLanguagesValues = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.dictate_default_input_languages)));
        currentInputLanguagesValues = sp.getStringSet("net.devemperor.dictate.input_languages", currentInputLanguagesValues);
        List<String> allLanguagesValues = Arrays.asList(getResources().getStringArray(R.array.dictate_input_languages_values));
        List<String> recordDifferentLanguages = Arrays.asList(getResources().getStringArray(R.array.dictate_record_different_languages));

        if (currentInputLanguagePos >= currentInputLanguagesValues.size()) currentInputLanguagePos = 0;
        sp.edit().putInt("net.devemperor.dictate.input_language_pos", currentInputLanguagePos).apply();

        currentInputLanguageValue = currentInputLanguagesValues.toArray()[currentInputLanguagePos].toString();
        return recordDifferentLanguages.get(allLanguagesValues.indexOf(currentInputLanguagesValues.toArray()[currentInputLanguagePos].toString()));
    }

    private void deleteOneCharacter() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection != null) {
            CharSequence selectedText = inputConnection.getSelectedText(0);

            if (selectedText != null) {
                inputConnection.commitText("", 1);
            } else {
                inputConnection.deleteSurroundingText(1, 0);
            }
        }
    }

    // checks whether a point is inside a view based on its horizontal position
    private boolean isPointInsideView(float x, View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return x > location[0] && x < location[0] + view.getWidth();
    }

    private void highlightSelectedCharacter(TextView selectedView) {
        for (int i = 0; i < overlayCharactersLl.getChildCount(); i++) {
            TextView charView = (TextView) overlayCharactersLl.getChildAt(i);
            if (charView == selectedView) {
                charView.setBackground(AppCompatResources.getDrawable(this, R.drawable.border_textview_selected));
            } else {
                charView.setBackground(AppCompatResources.getDrawable(this, R.drawable.border_textview));
            }
        }
    }

    @Override
    public void onDestroy() {
        Log.d("DictateService", "onDestroy called. Cleaning up resources.");

        // Release MediaRecorder if active
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) {
                // Already stopped or not started
            }
            recorder.release();
            recorder = null;
        }

        // Remove handler callbacks
        if (recordTimeHandler != null && recordTimeRunnable != null) {
            recordTimeHandler.removeCallbacks(recordTimeRunnable);
        }
        if (deleteHandler != null && deleteRunnable != null) {
            deleteHandler.removeCallbacks(deleteRunnable);
        }
        // mainHandler typically doesn't need messages removed unless they are long-lived
        // and could cause issues if the service context is gone.

        // Shutdown ExecutorServices
        if (speechApiThread != null && !speechApiThread.isShutdown()) {
            speechApiThread.shutdownNow();
            speechApiThread = null;
            Log.d("DictateService", "Speech API thread shutdown.");
        }
        if (rewordingApiThread != null && !rewordingApiThread.isShutdown()) {
            rewordingApiThread.shutdownNow();
            rewordingApiThread = null;
            Log.d("DictateService", "Rewording API thread shutdown.");
        }

        if (timeoutHandler != null && timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
        }

        // Abandon audio focus
        if (am != null && audioFocusRequest != null && audioFocusEnabled) {
            am.abandonAudioFocusRequest(audioFocusRequest);
            Log.d("DictateService", "Audio focus abandoned.");
        }

        // Close databases (optional here, as they are usually managed by helpers,
        // but good for completeness if you open them directly)
        if (promptsDb != null) {
            promptsDb.close();
        }
        if (usageDb != null) {
            usageDb.close();
        }
        
        super.onDestroy();
    }
}
