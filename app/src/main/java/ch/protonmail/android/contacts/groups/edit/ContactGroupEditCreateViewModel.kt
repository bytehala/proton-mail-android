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
package ch.protonmail.android.contacts.groups.edit

import android.annotation.SuppressLint
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import android.text.TextUtils
import ch.protonmail.android.api.models.ResponseBody
import ch.protonmail.android.api.models.room.contacts.ContactEmail
import ch.protonmail.android.api.models.room.contacts.ContactLabel
import ch.protonmail.android.api.rx.RxEventBus
import ch.protonmail.android.api.rx.ThreadSchedulers
import ch.protonmail.android.contacts.ErrorEnum
import ch.protonmail.android.contacts.PostResult
import ch.protonmail.android.core.Constants
import ch.protonmail.android.core.UserManager
import ch.protonmail.android.events.Status
import ch.protonmail.android.utils.Event
import ch.protonmail.android.utils.extensions.toPMResponseBody
import io.reactivex.Completable
import retrofit2.HttpException
import javax.inject.Inject

/**
 * Created by kadrikj on 9/5/18. */
class ContactGroupEditCreateViewModel @Inject constructor(private val userManager: UserManager,
        private val contactGroupEditCreateRepository: ContactGroupEditCreateRepository) : ViewModel() {

    private var contactLabel: ContactLabel? = null
    private lateinit var _data: List<ContactEmail>
    private lateinit var _toBeDeleted: List<ContactEmail>
    private lateinit var _toBeAdded: List<ContactEmail>
    private lateinit var mode: ContactGroupMode
    private var _changed: Boolean = false

    private val _contactGroupSetupLayout: MutableLiveData<Event<ContactGroupMode>> = MutableLiveData()
    private val _contactGroupEmailsResult: MutableLiveData<List<ContactEmail>> = MutableLiveData()
    private val _contactGroupEmailsError: MutableLiveData<Event<String>> = MutableLiveData()
    private val _contactGroupUpdateResult: MutableLiveData<Event<PostResult>> = MutableLiveData()
    private val _cleanUpComplete: MutableLiveData<Event<Boolean>> = MutableLiveData()

    private val _apiError: (ResponseBody) -> Unit = {
        // todo show the error to the user
        val msg = it.error
    }
    val contactGroupEmailsResult: LiveData<List<ContactEmail>>
        get() = _contactGroupEmailsResult

    val contactGroupSetupLayout: LiveData<Event<ContactGroupMode>>
        get() = _contactGroupSetupLayout

    val contactGroupEmailsError: LiveData<Event<String>>
        get() = _contactGroupEmailsError

    val contactGroupUpdateResult: LiveData<Event<PostResult>>
        get() = _contactGroupUpdateResult

    val cleanUpComplete: LiveData<Event<Boolean>>
        get() = _cleanUpComplete

    init {
        RxEventBus.listen(ResponseBody::class.java).subscribe(_apiError)
    }

    fun setData(contactLabel: ContactLabel?) {
        this.contactLabel = contactLabel
        mode = if (contactLabel == null) ContactGroupMode.CREATE else ContactGroupMode.EDIT
        _contactGroupSetupLayout.postValue(Event(mode))
    }

    fun getGroupName(): String? = contactLabel?.name

    fun getGroupColor(): String? = contactLabel?.color

    fun setGroupColor(color: String) {
        if (contactLabel == null) {
            contactLabel = ContactLabel()
        }
        contactLabel?.color = color
    }

    fun getContactGroupEmails() {
        val label = contactLabel
        if (label != null)
            contactGroupEditCreateRepository.getContactGroupEmails(label.ID)
                    .subscribeOn(ThreadSchedulers.io())
                    .observeOn(ThreadSchedulers.main()).subscribe(
                            {
                                _data = it
                                _contactGroupEmailsResult.postValue(it)
                            },
                            {
                                _contactGroupEmailsError.value = Event(it.message ?: ErrorEnum.INVALID_EMAIL_LIST.name)
                            }
                    )
        else {
            _data = ArrayList()
            _contactGroupEmailsResult.postValue(ArrayList())
        }
        _toBeAdded = ArrayList()
        _toBeDeleted = ArrayList()
    }

    fun save(name: String) {
        val paidUser = userManager.user.isPaidUser
        if (!paidUser) {
            _contactGroupUpdateResult.postValue(Event(PostResult(status = Status.UNAUTHORIZED)))
            return
        }
        when (mode) {
            ContactGroupMode.CREATE ->
                createContactGroup(name, _toBeAdded.mapNotNull {
                    it.contactEmailId
                })
            ContactGroupMode.EDIT ->
                editContactGroup(name, _toBeAdded.mapNotNull {
                    it.contactEmailId
                }, _toBeDeleted.mapNotNull {
                    it.contactEmailId
                })
        }
    }

    @SuppressLint("CheckResult")
    private fun editContactGroup(name: String, toBeAdded: List<String>, toBeDeleted:List<String>) {
        val contactLabel = ContactLabel(ID = contactLabel!!.ID, name = name, color = contactLabel!!.color, display = contactLabel!!.display, exclusive = contactLabel!!.exclusive, type = contactLabel!!.type)
        contactGroupEditCreateRepository.editContactGroup(contactLabel)
                .doOnError {
                    if (it is HttpException) {
                        val responseBody = it.toPMResponseBody()
                        _contactGroupUpdateResult.postValue(Event(PostResult(responseBody?.error, Status.FAILED)))
                    }
                }
                .concatWith(
                    contactGroupEditCreateRepository.setMembersForContactGroup(contactLabel!!.ID, name, toBeAdded)
                )
                .concatWith(
                    contactGroupEditCreateRepository.removeMembersFromContactGroup(contactLabel!!.ID, name, toBeDeleted)
                )
                .subscribeOn(ThreadSchedulers.io())
                .observeOn(ThreadSchedulers.main())
                .subscribe({
                    _contactGroupUpdateResult.postValue(Event(PostResult(status = Status.SUCCESS)))
                }, {
                    var message = it.message
                    if (it is HttpException) {
                        message = it.toPMResponseBody()?.error
                    }
                    _contactGroupUpdateResult.postValue(Event(PostResult(message, Status.FAILED)))
                })
    }

    @SuppressLint("CheckResult")
    private fun createContactGroup(name: String, membersList: List<String>) {
        val contactLabel = ContactLabel(name = name, color = contactLabel!!.color, display = 1, exclusive = false, type = Constants.LABEL_TYPE_CONTACT_GROUPS)
        if (!validate(contactLabel)) {
            _contactGroupUpdateResult.postValue(Event(PostResult(status = Status.VALIDATION_FAILED)))
            return
        }
        contactGroupEditCreateRepository.createContactGroup(contactLabel)
                .onErrorReturn {
                    if (it is HttpException) {
                        val responseBody = it.toPMResponseBody()
                        _contactGroupUpdateResult.postValue(Event(PostResult(responseBody?.error, Status.FAILED)))
                    }
                    contactLabel
                }
                .flatMapCompletable {
                    Completable.complete().andThen(contactGroupEditCreateRepository.setMembersForContactGroup(it.ID, name, membersList))
                }
                .subscribeOn(ThreadSchedulers.io())
                .observeOn(ThreadSchedulers.main())
                .subscribe({
                    _contactGroupUpdateResult.postValue(Event(PostResult(status = Status.SUCCESS)))
                }, {
                    var message = it.message
                    if (it is HttpException) {
                        message = it.toPMResponseBody()?.error
                    }
                    _contactGroupUpdateResult.postValue(Event(PostResult(message, Status.FAILED)))
                })
    }

    private fun validate(contactLabel: ContactLabel) : Boolean {
        if (TextUtils.isEmpty(contactLabel.name)) {
            return false
        }
        return true
    }

    fun setChanged() {
        _changed = true
    }

    /**
     * Should inform the view that the user want's to leave the screen, so that the view is able to save
     * state or do whatever needed for cleanup.
     */
    fun onBackPressed() {
        _cleanUpComplete.postValue(Event(!_changed))
    }

    fun calculateDiffMembers(newMembers: List<ContactEmail>) {
        _toBeDeleted = _data.minus(newMembers) // to be deleted
        _toBeAdded = newMembers.minus(_data) // to be added
        _contactGroupEmailsResult.postValue(newMembers)
    }
}