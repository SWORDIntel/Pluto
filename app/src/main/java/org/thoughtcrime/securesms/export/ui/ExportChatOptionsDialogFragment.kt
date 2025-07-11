package org.thoughtcrime.securesms.export.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels // For by viewModels()
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import org.thoughtcrime.securesms.R // Assuming this is the correct R file import

// Listener interface for dialog actions
import kotlinx.parcelize.Parcelize

// Listener interface for dialog actions
interface ExportOptionsListener {
    fun onExportSelected(options: ExportOptions, threadId: Long)
}

@Parcelize
data class ExportOptions(
    val format: ExportFormat,
    val destination: ExportDestination,
    val apiUrl: String?,
    val type: ExportType,
    val frequency: ExportFrequency?
) : Parcelable

@Parcelize
enum class ExportFormat : Parcelable { JSON, CSV }
@Parcelize
enum class ExportDestination : Parcelable { LOCAL_FILE, API_ENDPOINT }
@Parcelize
enum class ExportType : Parcelable { ONETIME, SCHEDULED }
@Parcelize
enum class ExportFrequency : Parcelable { DAILY, WEEKLY, MONTHLY } // Matches conceptual string-array

class ExportChatOptionsDialogFragment : DialogFragment() {

    private lateinit var formatGroup: RadioGroup
    private lateinit var destinationGroup: RadioGroup
    private lateinit var apiUrlInputLayout: TextInputLayout
    private lateinit var apiUrlEditText: TextInputEditText
    private lateinit var typeGroup: RadioGroup
    private lateinit var schedulingOptionsLayout: View
    private lateinit var frequencySpinner: Spinner
    private lateinit var cancelButton: Button
    private lateinit var exportButton: Button

    private val exportViewModel: ExportViewModel by viewModels() // ViewModel instance

    // Listener interface might become obsolete or change if all actions are via ViewModel
    var listener: ExportOptionsListener? = null
    private var threadId: Long = -1L

    companion object {
        const val TAG = "ExportChatOptionsDialog"
        private const val ARG_THREAD_ID = "thread_id"
        private const val ARG_TEST_OPTIONS = "test_options" // Key for test options

        fun newInstance(threadId: Long): ExportChatOptionsDialogFragment {
            val fragment = ExportChatOptionsDialogFragment()
            val args = Bundle()
            args.putLong(ARG_THREAD_ID, threadId)
            fragment.arguments = args
            return fragment
        }

        fun newInstanceForTesting(threadId: Long, testOptions: ExportOptions): ExportChatOptionsDialogFragment {
            val fragment = ExportChatOptionsDialogFragment()
            val args = Bundle()
            args.putLong(ARG_THREAD_ID, threadId)
            args.putParcelable(ARG_TEST_OPTIONS, testOptions)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Set listener from context or parent fragment - this might be removed if ViewModel handles all
        listener = when {
            context is ExportOptionsListener -> context
            parentFragment is ExportOptionsListener -> parentFragment as ExportOptionsListener
            targetFragment is ExportOptionsListener -> targetFragment as ExportOptionsListener
            else -> null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        threadId = arguments?.getLong(ARG_THREAD_ID) ?: -1L
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val testOptions: ExportOptions? = arguments?.getParcelable(ARG_TEST_OPTIONS)
        if (testOptions != null) {
            // In test mode, directly use the ViewModel.
            // The listener from onAttach might not be relevant if the dialog is dismissed quickly.
            if (threadId != -1L) {
                exportViewModel.startExportOrSchedule(requireContext(), threadId, testOptions)
                // Return an empty dialog that will be dismissed in onStart
                return AlertDialog.Builder(requireContext()).create()
            } else {
                Toast.makeText(requireContext(), "Test mode error: ThreadID missing.", Toast.LENGTH_LONG).show()
            }
        }

        val builder = AlertDialog.Builder(requireContext())
        val inflater = LayoutInflater.from(requireContext())

        // Conceptual R.layout.dialog_export_chat_options, assuming it exists and matches the XML described in Plan Step 6
        val view = inflater.inflate(R.layout.dialog_export_chat_options, null)

        // Initialize UI elements from view
        formatGroup = view.findViewById(R.id.export_format_radio_group)
        destinationGroup = view.findViewById(R.id.export_destination_radio_group)
        apiUrlInputLayout = view.findViewById(R.id.api_url_input_layout)
        apiUrlEditText = view.findViewById(R.id.api_url_edit_text)
        typeGroup = view.findViewById(R.id.export_type_radio_group)
        schedulingOptionsLayout = view.findViewById(R.id.scheduling_options_layout)
        frequencySpinner = view.findViewById(R.id.schedule_frequency_spinner)
        cancelButton = view.findViewById(R.id.cancel_button)
        exportButton = view.findViewById(R.id.export_button)

        // Setup spinner for frequency
        // Assumes R.array.export_schedule_frequencies is defined in strings.xml: <item>Daily</item><item>Weekly</item><item>Monthly</item>
        ArrayAdapter.createFromResource(
            requireContext(),
            R.array.export_schedule_frequencies,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            frequencySpinner.adapter = adapter
        }

        // Setup listeners for radio groups to show/hide conditional UI
        destinationGroup.setOnCheckedChangeListener { _, checkedId ->
            apiUrlInputLayout.visibility = if (checkedId == R.id.destination_api_radio_button) View.VISIBLE else View.GONE
        }
        typeGroup.setOnCheckedChangeListener { _, checkedId ->
            schedulingOptionsLayout.visibility = if (checkedId == R.id.type_scheduled_radio_button) View.VISIBLE else View.GONE
        }

        // Set initial visibility based on default checked states (assuming defaults are set in XML or first item is checked)
        // Note: R.id.destination_api_radio_button might not be checked by default, ensure XML has a default or handle here.
        apiUrlInputLayout.visibility = if (destinationGroup.checkedRadioButtonId == R.id.destination_api_radio_button) View.VISIBLE else View.GONE
        schedulingOptionsLayout.visibility = if (typeGroup.checkedRadioButtonId == R.id.type_scheduled_radio_button) View.VISIBLE else View.GONE

        builder.setView(view)
        // Buttons are handled by the layout's buttons, so no need for builder.setPositive/NegativeButton

        cancelButton.setOnClickListener { dismiss() }

        exportButton.setOnClickListener {
            val selectedFormat = if (formatGroup.checkedRadioButtonId == R.id.format_json_radio_button) ExportFormat.JSON else ExportFormat.CSV
            val selectedDestination = if (destinationGroup.checkedRadioButtonId == R.id.destination_local_file_radio_button) ExportDestination.LOCAL_FILE else ExportDestination.API_ENDPOINT
            val apiUrlText = apiUrlEditText.text.toString().trim()

            val selectedType = if (typeGroup.checkedRadioButtonId == R.id.type_onetime_radio_button) ExportType.ONETIME else ExportType.SCHEDULED

            val selectedFrequency: ExportFrequency? = if (selectedType == ExportType.SCHEDULED) {
                when (frequencySpinner.selectedItemPosition) {
                    0 -> ExportFrequency.DAILY
                    1 -> ExportFrequency.WEEKLY
                    2 -> ExportFrequency.MONTHLY
                    else -> null // Should ideally have a default or handle error
                }
            } else null

            if (selectedDestination == ExportDestination.API_ENDPOINT && apiUrlText.isBlank()) {
                apiUrlInputLayout.error = "API URL cannot be empty" // Show error on the TextInputLayout
                return@setOnClickListener
            } else {
                apiUrlInputLayout.error = null // Clear error
            }

            if (threadId == -1L) {
                Toast.makeText(requireContext(), "Error: Thread ID not found.", Toast.LENGTH_SHORT).show()
                dismiss()
                return@setOnClickListener
            }

            if (selectedType == ExportType.SCHEDULED && selectedFrequency == null) {
                 Toast.makeText(requireContext(), "Please select a schedule frequency.", Toast.LENGTH_SHORT).show()
                 return@setOnClickListener
            }

            // Call the ViewModel instead of the listener directly
            exportViewModel.startExportOrSchedule(
                requireContext(),
                threadId,
                ExportOptions(
                    format = selectedFormat,
                    destination = selectedDestination,
                    apiUrl = if (selectedDestination == ExportDestination.API_ENDPOINT) apiUrlText else null,
                    type = selectedType,
                    frequency = selectedFrequency
                )
            )
            dismiss()
        }

        return builder.create()
    }

    override fun onStart() {
        super.onStart()
        // If this dialog was created with test options, it should be dismissed.
        // The listener callback happens in onCreateDialog, and an empty dialog is returned.
        // This ensures that empty dialog is dismissed.
        if (arguments?.containsKey(ARG_TEST_OPTIONS) == true) {
            if (dialog?.isShowing == true) {
                dismissAllowingStateLoss()
            }
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null // Avoid memory leaks
    }
}
