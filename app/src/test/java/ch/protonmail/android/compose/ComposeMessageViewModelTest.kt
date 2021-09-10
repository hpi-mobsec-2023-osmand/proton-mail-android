/*
 * Copyright (c) 2020 Proton Technologies AG
 *
 * This file is part of ProtonMail.
 *
 * ProtonMail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonMail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonMail. If not, see https://www.gnu.org/licenses/.
 */

package ch.protonmail.android.compose

import androidx.work.WorkManager
import ch.protonmail.android.R
import ch.protonmail.android.activities.messageDetails.repository.MessageDetailsRepository
import ch.protonmail.android.api.NetworkConfigurator
import ch.protonmail.android.api.models.factories.MessageSecurityOptions
import ch.protonmail.android.compose.presentation.model.AddExpirationTimeToMessage
import ch.protonmail.android.compose.presentation.model.MessagePasswordUiModel
import ch.protonmail.android.compose.presentation.util.HtmlToSpanned
import ch.protonmail.android.compose.send.SendMessage
import ch.protonmail.android.core.Constants
import ch.protonmail.android.core.UserManager
import ch.protonmail.android.data.local.model.Message
import ch.protonmail.android.testAndroid.lifecycle.testObserver
import ch.protonmail.android.testAndroid.rx.TrampolineScheduler
import ch.protonmail.android.usecase.VerifyConnection
import ch.protonmail.android.usecase.compose.SaveDraft
import ch.protonmail.android.usecase.compose.SaveDraftResult
import ch.protonmail.android.usecase.delete.DeleteMessage
import ch.protonmail.android.usecase.fetch.FetchPublicKeys
import ch.protonmail.android.utils.UiUtil
import ch.protonmail.android.utils.resources.StringResourceResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runBlockingTest
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.test.android.ArchTest
import me.proton.core.test.kotlin.CoroutinesTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull

class ComposeMessageViewModelTest : ArchTest, CoroutinesTest {

    @get:Rule
    val trampolineSchedulerRule = TrampolineScheduler()

    private val stringResourceResolver: StringResourceResolver = mockk(relaxed = true)

    private val composeMessageRepository: ComposeMessageRepository = mockk(relaxed = true)

    private val userManager: UserManager = mockk(relaxed = true) {
        every { requireCurrentLegacyUser().senderEmailAddresses } returns mutableListOf()
    }

    private val accountManager: AccountManager = mockk(relaxed = true) {
    }

    private val messageDetailsRepository: MessageDetailsRepository = mockk(relaxed = true) {
        coEvery { saveMessage(any()) } returns 1L
    }

    private val saveDraft: SaveDraft = mockk(relaxed = true)

    private val sendMessage: SendMessage = mockk(relaxed = true)

    private val deleteMessage: DeleteMessage = mockk()

    private val fetchPublicKeys: FetchPublicKeys = mockk()

    private val networkConfigurator: NetworkConfigurator = mockk()

    private val verifyConnection: VerifyConnection = mockk()

    private val workManager: WorkManager = mockk(relaxed = true)

    private val htmlToSpanned: HtmlToSpanned = mockk(relaxed = true)

    private val addExpirationTimeToMessage: AddExpirationTimeToMessage = mockk()

    private val viewModel = ComposeMessageViewModel(
        composeMessageRepository = composeMessageRepository,
        userManager = userManager,
        accountManager = accountManager,
        messageDetailsRepository = messageDetailsRepository,
        deleteMessage = deleteMessage,
        fetchPublicKeys = fetchPublicKeys,
        saveDraft = saveDraft,
        dispatchers = dispatchers,
        stringResourceResolver = stringResourceResolver,
        sendMessage = sendMessage,
        verifyConnection = verifyConnection,
        networkConfigurator = networkConfigurator,
        htmlToSpanned = htmlToSpanned,
        addExpirationTimeToMessage = addExpirationTimeToMessage
    )

    @BeforeTest
    fun setUp() {
        mockkStatic(UiUtil::class)
        every { verifyConnection.invoke() } returns flowOf(Constants.ConnectionState.CONNECTED)
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic(UiUtil::class)
    }

    @Test
    fun saveDraftCallsSaveDraftUseCaseWithUserRequestedTriggerWhenTheDraftIsNewAndTheUserDidRequestSaving() {
        runBlockingTest {
            // Given
            val message = Message()
            givenViewModelPropertiesAreInitialised()
            // This indicates that saving draft was requested by the user
            viewModel.setUploadAttachments(true)
            coEvery { saveDraft(any()) } returns SaveDraftResult.Success("draftId")
            coEvery { messageDetailsRepository.findMessageById("draftId") } returns flowOf(message)

            // When
            viewModel.saveDraft(message, hasConnectivity = false)

            // Then
            val parameters = SaveDraft.SaveDraftParameters(
                message,
                emptyList(),
                "parentId823",
                Constants.MessageActionType.FORWARD,
                "previousSenderAddressId",
                SaveDraft.SaveDraftTrigger.UserRequested
            )
            coVerify { saveDraft(parameters) }
        }
    }

    @Test
    fun saveDraftCallsSaveDraftUseCaseWithAutoSaveTriggerWhenTheDraftIsNewAndTheUserDidNotRequestSaving() {
        runBlockingTest {
            // Given
            val message = Message()
            givenViewModelPropertiesAreInitialised()
            // This indicates that saving draft was not requested by the user
            viewModel.setUploadAttachments(false)
            coEvery { saveDraft(any()) } returns SaveDraftResult.Success("draftId")
            coEvery { messageDetailsRepository.findMessageById("draftId") } returns flowOf(message)

            // When
            viewModel.saveDraft(message, hasConnectivity = false)

            // Then
            val parameters = SaveDraft.SaveDraftParameters(
                message,
                emptyList(),
                "parentId823",
                Constants.MessageActionType.FORWARD,
                "previousSenderAddressId",
                SaveDraft.SaveDraftTrigger.AutoSave
            )
            coVerify { saveDraft(parameters) }
        }
    }

    @Test
    fun saveDraftReadsNewlyCreatedDraftFromRepositoryAndPostsItToLiveDataWhenSaveDraftUseCaseSucceeds() {
        runBlockingTest {
            // Given
            val message = Message()
            val createdDraftId = "newDraftId"
            val createdDraft = Message(messageId = createdDraftId, localId = "local28348")
            val savedDraftObserver = viewModel.savingDraftComplete.testObserver()
            givenViewModelPropertiesAreInitialised()
            coEvery { saveDraft(any()) } returns SaveDraftResult.Success(createdDraftId)
            coEvery { messageDetailsRepository.findMessageById(createdDraftId) } returns flowOf(createdDraft)

            // When
            viewModel.saveDraft(message, hasConnectivity = false)

            coVerify { messageDetailsRepository.findMessageById(createdDraftId) }
            assertEquals(createdDraft, savedDraftObserver.observedValues[0])
        }
    }

    @Test
    fun saveDraftObservesMessageInComposeRepositoryToGetNotifiedWhenMessageIsSent() {
        runBlockingTest {
            // Given
            val createdDraftId = "newDraftId"
            val localDraftId = "localDraftId"
            val createdDraft = Message(messageId = createdDraftId, localId = localDraftId)
            givenViewModelPropertiesAreInitialised()
            coEvery { saveDraft(any()) } returns SaveDraftResult.Success(createdDraftId)
            coEvery { messageDetailsRepository.findMessageById(createdDraftId) } returns flowOf(createdDraft)

            // When
            viewModel.saveDraft(Message(), hasConnectivity = false)

            // Then
            assertEquals(createdDraftId, viewModel.draftId)
            coVerify { composeMessageRepository.findMessageByIdObservable(createdDraftId) }
        }
    }

    @Test
    fun saveDraftCallsSaveDraftUseCaseWhenTheDraftIsExisting() {
        runBlockingTest {
            // Given
            val message = Message()
            givenViewModelPropertiesAreInitialised()
            viewModel.draftId = "non-empty-draftId"
            viewModel.setUploadAttachments(true)
            coEvery { saveDraft(any()) } returns SaveDraftResult.Success("draftId")
            coEvery { messageDetailsRepository.findMessageById("draftId") } returns flowOf(message)

            // When
            viewModel.saveDraft(message, hasConnectivity = false)

            // Then
            val parameters = SaveDraft.SaveDraftParameters(
                message,
                emptyList(),
                "parentId823",
                Constants.MessageActionType.FORWARD,
                "previousSenderAddressId",
                SaveDraft.SaveDraftTrigger.UserRequested
            )
            coVerify { saveDraft(parameters) }
        }
    }

    @Test
    fun saveDraftResolvesLocalisedErrorMessageAndPostsOnLiveDataWhenSaveDraftUseCaseFailsCreatingTheDraft() {
        runBlockingTest {
            // Given
            val messageSubject = "subject"
            val message = Message(subject = messageSubject)
            val saveDraftErrorObserver = viewModel.savingDraftError.testObserver()
            val errorResId = R.string.failed_saving_draft_online
            givenViewModelPropertiesAreInitialised()
            coEvery { saveDraft(any()) } returns SaveDraftResult.OnlineDraftCreationFailed
            every { stringResourceResolver.invoke(errorResId) } returns "Error creating draft for message %s"

            // When
            viewModel.saveDraft(message, hasConnectivity = true)

            val expectedError = "Error creating draft for message $messageSubject"
            coVerify { stringResourceResolver.invoke(errorResId) }
            assertEquals(expectedError, saveDraftErrorObserver.observedValues[0])
        }
    }

    @Test
    fun saveDraftResolvesLocalisedErrorMessageAndPostsOnLiveDataWhenSaveDraftUseCaseFailsUploadingAttachments() {
        runBlockingTest {
            // Given
            val messageSubject = "subject"
            val message = Message(subject = messageSubject)
            val saveDraftErrorObserver = viewModel.savingDraftError.testObserver()
            val errorResId = R.string.attachment_failed
            givenViewModelPropertiesAreInitialised()
            coEvery { saveDraft(any()) } returns SaveDraftResult.UploadDraftAttachmentsFailed
            every { stringResourceResolver.invoke(errorResId) } returns "Error uploading attachments for subject "

            // When
            viewModel.saveDraft(message, hasConnectivity = true)

            val expectedError = "Error uploading attachments for subject $messageSubject"
            coVerify { stringResourceResolver.invoke(errorResId) }
            assertEquals(expectedError, saveDraftErrorObserver.observedValues[0])
        }
    }

    @Test
    fun saveDraftReadsNewlyCreatedDraftFromRepositoryAndPostsItToLiveDataWhenUpdatingDraftAndSaveDraftUseCaseSucceeds() {
        runBlockingTest {
            // Given
            val message = Message()
            val updatedDraftId = "updatedDraftId"
            val updatedDraft = Message(messageId = updatedDraftId, localId = "local82347")
            val savedDraftObserver = viewModel.savingDraftComplete.testObserver()
            givenViewModelPropertiesAreInitialised()
            viewModel.draftId = "non-empty draftId triggers update draft"
            coEvery { saveDraft(any()) } returns SaveDraftResult.Success(updatedDraftId)
            coEvery { messageDetailsRepository.findMessageById(updatedDraftId) } returns flowOf(updatedDraft)

            // When
            viewModel.saveDraft(message, hasConnectivity = false)

            coVerify { messageDetailsRepository.findMessageById(updatedDraftId) }
            assertEquals(updatedDraft, savedDraftObserver.observedValues[0])
        }
    }

    @Test
    fun autoSaveDraftSchedulesJobToPerformSaveDraftAfterSomeDelayWithUploadAttachmentsFalse() {
        // It's important to check 'uploadAttachments' boolean flag as we rely on it to
        // define the saveDraft trigger (AutoSave when uploadAttachments is false)
        runBlockingTest(dispatchers.Io) {
            // Given
            val messageBody = "Message body being edited..."
            val messageId = "draft8237472"
            val message = Message(messageId, subject = "A subject")
            val buildMessageObserver = viewModel.buildingMessageCompleted.testObserver()
            givenViewModelPropertiesAreInitialised()
            // message was already saved once (we're updating)
            viewModel.draftId = messageId
            every { UiUtil.toHtml(messageBody) } returns "<html> $messageBody <html>"
            coEvery { composeMessageRepository.findMessage(messageId) } returns message
            coEvery { composeMessageRepository.createAttachmentList(any(), dispatchers.Io) } returns emptyList()

            // When
            viewModel.autoSaveDraft(messageBody)
            viewModel.autoSaveJob?.join()

            // Then
            val expectedMessage = message.copy()
            assertEquals(expectedMessage, buildMessageObserver.observedValues[0]?.peekContent())
            assertEquals("&lt;html&gt; Message body being edited... &lt;html&gt;", viewModel.messageDataResult.content)
            assertEquals(false, viewModel.messageDataResult.uploadAttachments)
        }
    }

    @Test
    fun autoSaveDraftCancelsExistingJobBeforeSchedulingANewOneWhenCalledTwice() {
        runBlockingTest(dispatchers.Io) {
            // Given
            val messageBody = "Message body being edited again..."
            val messageId = "draft923823"
            val message = Message(messageId, subject = "Another subject")
            viewModel.buildingMessageCompleted.testObserver()
            givenViewModelPropertiesAreInitialised()
            // message was already saved once (we're updating)
            viewModel.draftId = messageId
            every { UiUtil.toHtml(messageBody) } returns "<html> $messageBody <html>"
            coEvery { composeMessageRepository.findMessage(messageId) } returns message
            coEvery { composeMessageRepository.createAttachmentList(any(), dispatchers.Io) } returns emptyList()

            // When
            viewModel.autoSaveDraft(messageBody)
            assertNotNull(viewModel.autoSaveJob)
            val firstScheduledJob = viewModel.autoSaveJob
            viewModel.autoSaveDraft(messageBody)

            // Then
            assertTrue(firstScheduledJob?.isCancelled ?: false)
            assertTrue(viewModel.autoSaveJob?.isActive ?: false)
            assertEquals(false, viewModel.messageDataResult.uploadAttachments)
        }
    }

    @Test
    fun sendMessageCallsSendMessageUseCaseWithMessageParameters() {
        runBlockingTest {
            // Given
            val message = Message(messageId = "message id")
            val messageWithExpirationTime = message.copy(expirationTime = 42L)
            givenViewModelPropertiesAreInitialised()
            viewModel.setPassword(MessagePasswordUiModel.Set("messagePassword", "a hint to discover it"))
            every { workManager.cancelUniqueWork(any()) } returns mockk()
            every { addExpirationTimeToMessage(eq(message), any()) } returns messageWithExpirationTime

            // When
            viewModel.sendMessage(message)

            // Then
            val params = SendMessage.SendMessageParameters(
                messageWithExpirationTime,
                listOf(),
                "parentId823",
                Constants.MessageActionType.FORWARD,
                "previousSenderAddressId",
                MessageSecurityOptions("messagePassword", "a hint to discover it", 0)
            )
            coVerify { sendMessage(params) }
        }
    }

    @Test
    fun saveDraftUpdatesOldSenderAddressIdAfterUpdatingADraft() {
        runBlockingTest {
            // Given
            // Setting sender address on message simulates the user changing the address
            val message = Message(addressID = "changedSenderAddress")
            val updatedDraftId = "updatedDraftId"
            val updatedDraft = Message(messageId = updatedDraftId, localId = "local82347")
            givenViewModelPropertiesAreInitialised()
            // This value was set to empty during initial draft creation
            viewModel.oldSenderAddressId = ""
            viewModel.draftId = "non-empty draftId triggers update draft"
            coEvery { saveDraft(any()) } returns SaveDraftResult.Success(updatedDraftId)
            coEvery { messageDetailsRepository.findMessageById(updatedDraftId) } returns flowOf(updatedDraft)

            // When
            viewModel.saveDraft(message, hasConnectivity = false)

            // Then
            coVerify { messageDetailsRepository.findMessageById(updatedDraftId) }
            assertEquals("changedSenderAddress", viewModel.oldSenderAddressId)
        }
    }

    private fun givenViewModelPropertiesAreInitialised() {
        // Needed to set class fields to the right value and allow code under test to get executed
        viewModel.prepareMessageData(false, "addressId", "mail-alias")
        viewModel.setupComposingNewMessage(Constants.MessageActionType.FORWARD, "parentId823", "")
        viewModel.oldSenderAddressId = "previousSenderAddressId"
    }

}
