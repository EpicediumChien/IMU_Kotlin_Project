package com.example.myfirstkotlinapp

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.cognitoidentity.CognitoIdentityClient
import aws.sdk.kotlin.services.cognitoidentity.model.GetCredentialsForIdentityRequest
import aws.sdk.kotlin.services.cognitoidentity.model.GetIdRequest
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.content.ByteStream
import com.example.myfirstkotlinapp.viewmodel.ImuRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// --- Configuration ---
private const val BUCKET_NAME = "imu-s3-bucket"
private const val AWS_REGION = "ap-northeast-1"
private const val COGNITO_IDENTITY_POOL_ID = "ap-northeast-1:2c61210a-9870-4e31-bb36-675dfadfdb00"

class CsvUploadViewModel : ViewModel() {

    private var s3Client: S3Client? = null

    /**
     * initializes the S3 client using suspend functions to avoid freezing the UI.
     */
    private suspend fun getS3Client(): S3Client? {
        if (s3Client != null) return s3Client

        return withContext(Dispatchers.IO) {
            try {
                val cognitoIdentityClient = CognitoIdentityClient { region = AWS_REGION }

                // 1. Get Identity ID
                val getIdResponse = cognitoIdentityClient.getId(GetIdRequest {
                    identityPoolId = COGNITO_IDENTITY_POOL_ID
                })
                val identityId = getIdResponse.identityId ?: return@withContext null

                // 2. Get Credentials
                val getCredentialsResponse = cognitoIdentityClient.getCredentialsForIdentity(
                    GetCredentialsForIdentityRequest { this.identityId = identityId }
                )
                val tempCreds = getCredentialsResponse.credentials ?: return@withContext null

                // 3. Create Provider
                val staticProvider = StaticCredentialsProvider(
                    Credentials(
                        accessKeyId = tempCreds.accessKeyId ?: "",
                        secretAccessKey = tempCreds.secretKey ?: "",
                        sessionToken = tempCreds.sessionToken
                    )
                )

                // 4. Create Client
                val client = S3Client {
                    region = AWS_REGION
                    credentialsProvider = staticProvider
                }
                s3Client = client
                client
            } catch (e: Exception) {
                Log.e("S3ViewModel", "Auth Error: ${e.message}")
                null
            }
        }
    }

    fun uploadImuData(data: List<ImuRecord>) {
        if (data.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val csvContent = buildCsvString(data)

            // Create a unique filename based on time
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val objectKey = "data/imu_log_$timestamp.csv"

            val client = getS3Client()
            if (client != null) {
                try {
                    val putRequest = PutObjectRequest {
                        bucket = BUCKET_NAME
                        key = objectKey
                        body = ByteStream.fromString(csvContent)
                        contentType = "text/csv"
                    }
                    client.putObject(putRequest)
                    Log.i("S3ViewModel", "Upload Success: $objectKey (${data.size} records)")
                } catch (e: Exception) {
                    Log.e("S3ViewModel", "Upload Failed: ${e.message}")
                }
            }
        }
    }

    private fun buildCsvString(data: List<ImuRecord>): String {
        val header = "timestamp,accX,accY,accZ,gyroX,gyroY,gyroZ,magX,magY,magZ,roll,pitch,yaw"
        val rows = data.joinToString("\n") { it.toCsvString() }
        return "$header\n$rows"
    }

    override fun onCleared() {
        super.onCleared()
        s3Client?.close()
    }
}