/*
 * Copyright (c) 2010-2020 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.activities.main

// gerenciar diálogos na interface do usuario
import android.app.Dialog
// permite a classe receber callback de mudança de config e outros eventos relacionados ao sistema
import android.content.ComponentCallbacks2
// contexto do app
import android.content.Context
// iniciar novas atividades e passar dados entre elas
import android.content.Intent
// informações de configuração sobre o dispositivo, como orientação de tela e tamanho
import android.content.res.Configuration
// representa identificador uniforme de recurso que pode ser usado para identificar dados dentro do app ou na net
import android.net.Uri
// passar dados entre as atividades
import android.os.Bundle
// interface para classes cujos objetos podem ser escritos e reconstruidos a partir de Parcel
import android.os.Parcelable
// constantes para posicionamento e alinhamento em layouts
import android.view.Gravity
// relatar movimentos (toques na tela)
import android.view.MotionEvent
// componentes de interface do usuario
import android.view.View
// gerencia a entrada e interação com campos de texto
import android.view.inputmethod.InputMethodManager
// indica um parametro, campo ou metodo e retorna um Id de recurso de string
import androidx.annotation.StringRes
// implementar tela de abertura para o app
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
// exxecutar uma ação quando a view é anexada à janela
import androidx.core.view.doOnAttach
// fornece metodos para vincular componentes de UI com fontes de dados do app usando modelo declarativo
import androidx.databinding.DataBindingUtil
// view especializado para conter fragments
import androidx.fragment.app.FragmentContainerView
// classe de dados observael que possui valor mutavel
import androidx.lifecycle.MutableLiveData
// instanciar viewmodels associados a uma UI
import androidx.lifecycle.ViewModelProvider
// proporciona um coroutineScope ligado ao lifecycle
import androidx.lifecycle.lifecycleScope
// gerencia a navegação app entre destinos de um navhost
import androidx.navigation.NavController
// representa um destino dentro do sistema de navegação do android x
import androidx.navigation.NavDestination
// expansao para recuperar um navcontroller diretamente de uma view
import androidx.navigation.findNavController
// detectar recursos de dobra em dispositivs de tela dobrável
import androidx.window.layout.FoldingFeature
// carregar imagens usando a biblioteca coil
import coil.imageLoader
// leve forma de feedback ao usuario
import com.google.android.material.snackbar.Snackbar
// exceção usada quando a codificação de caracteres nao é suportada
import java.io.UnsupportedEncodingException
// decoficar uma string codigifaca para URL
import java.net.URLDecoder
// fornece funções matemáticas básicas
import kotlin.math.abs
// importa coroutines para escrever código assíncrono
import kotlinx.coroutines.*
// acesso as configurações globais e o estado do core do sistema de VOIP
import org.linphone.LinphoneApplication.Companion.coreContext
// acesso às preferências de usuario armazenadas globalmente, como login, UI
import org.linphone.LinphoneApplication.Companion.corePreferences
// recursos de definição do android
import org.linphone.R
// importa todas as classes de atividades
import org.linphone.activities.*
// importa a atividade de assistente
import org.linphone.activities.assistant.AssistantActivity
// importa os view models, que ajudam a separar a logica de negocio da logica de apresentação
import org.linphone.activities.main.viewmodels.CallOverlayViewModel
import org.linphone.activities.main.viewmodels.DialogViewModel
import org.linphone.activities.main.viewmodels.SharedMainViewModel
// função de navegação especifica para direcionar o usuario pra tela de discagem
import org.linphone.activities.navigateToDialer
// checar compatibilidade de recursos dependendo da vers. do android
import org.linphone.compatibility.Compatibility
// listener que reage a atualizações na lista de contatos
import org.linphone.contact.ContactsUpdatedListenerStub
// importações do core 
import org.linphone.core.AuthInfo
import org.linphone.core.AuthMethod
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.CorePreferences
import org.linphone.core.tools.Log
// classe gerada para a vinculação de dados com o XML
import org.linphone.databinding.MainActivityBinding
// utilitários
import org.linphone.utils.*

class MainActivity : GenericActivity(), SnackBarActivity, NavController.OnDestinationChangedListener {
    // definições das models
    // contem os dados 
    private lateinit var binding: MainActivityBinding
    private lateinit var sharedViewModel: SharedMainViewModel
    // especifico para gerenciar a UI e o estado de sobreposição de chamadas
    private lateinit var callOverlayViewModel: CallOverlayViewModel

    // define um listener que reage à atualização dos contatos, quando os contatos são atualizados
    // verifica se há preferências especificas habilitadas para criar atalhos e, se true
    // cria metodos de ajuda para criar atalhos para contatos ou salas de chat
    private val listener = object : ContactsUpdatedListenerStub() {
        override fun onContactsUpdated() {
            Log.i("[Main Activity] Contact(s) updated, update shortcuts")
            if (corePreferences.contactsShortcuts) {
                ShortcutsHelper.createShortcutsToContacts(this@MainActivity)
            } else if (corePreferences.chatRoomShortcuts) {
                ShortcutsHelper.createShortcutsToChatRooms(this@MainActivity)
            }
        }
    }

    // referÇencia dos fragmentos
    // exibem diferentes seções da UI, aba de navegação e barra de status (na parte de cima do app)
    private lateinit var tabsFragment: FragmentContainerView
    private lateinit var statusFragment: FragmentContainerView

    // gerenciar a posição de um overlay
    private var overlayX = 0f
    private var overlayY = 0f
    private var initPosX = 0f
    private var initPosY = 0f
    private var overlay: View? = null

    // callback de componentes, implementa callback para gerenciar mudanças de configuração e eventos
    // relacionados à memoria, por ex: quando o disposito esta com pouca memoria, ele pode limpar o cache
    // para liberar recursos
    private val componentCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) { }

        override fun onLowMemory() {
            Log.w("[Main Activity] onLowMemory !")
        }

        override fun onTrimMemory(level: Int) {
            Log.w("[Main Activity] onTrimMemory called with level $level !")
            applicationContext.imageLoader.memoryCache?.clear()
        }
    }

    // é chamado quando há mudança de layout, quando um dispositivo dobravel é dobrado ou desdobrado
    // isso afeta como a UI deve ser apresentado
    override fun onLayoutChanges(foldingFeature: FoldingFeature?) {
        sharedViewModel.layoutChangedEvent.value = Event(true)
    }

    // determina se as abas devem ser visveis baseadas no destino atual da navegação
    private var shouldTabsBeVisibleDependingOnDestination = true
    // controla a visibilidade das abas com base na orientação do dispositivo e se o teclado esta visivel ou não
    private var shouldTabsBeVisibleDueToOrientationAndKeyboard = true

    // usado para transmitir eventos de autenticação que precisam ser tratados
    private val authenticationRequestedEvent: MutableLiveData<Event<AuthInfo>> by lazy {
        MutableLiveData<Event<AuthInfo>>()
    }
    // dialogo opcional que é apresentado quando uma autenticação é requerida
    private var authenticationRequiredDialog: Dialog? = null

    // ouvinte que responde a autenticação do linphone
    private val coreListener: CoreListenerStub = object : CoreListenerStub() {
        override fun onAuthenticationRequested(core: Core, authInfo: AuthInfo, method: AuthMethod) {
            if (authInfo.username == null || authInfo.domain == null || authInfo.realm == null) {
                return
            }

            Log.w(
                "[Main Activity] Authentication requested for account [${authInfo.username}@${authInfo.domain}] with realm [${authInfo.realm}] using method [$method]"
            )
            authenticationRequestedEvent.value = Event(authInfo)
        }
    }

    // uma lista de KeyboardVisibilityListener que são notificandos quando a visiblidade do teclado muda
    private val keyboardVisibilityListeners = arrayListOf<AppUtils.KeyboardVisibilityListener>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Must be done before the setContentView
        // instala a tela da splash
        installSplashScreen()

        // configura a view com Data Binding
        binding = DataBindingUtil.setContentView(this, R.layout.main_activity)
        binding.lifecycleOwner = this

        // inicializa os ViewModels
        sharedViewModel = ViewModelProvider(this)[SharedMainViewModel::class.java]
        binding.viewModel = sharedViewModel

        callOverlayViewModel = ViewModelProvider(this)[CallOverlayViewModel::class.java]
        binding.callOverlayViewModel = callOverlayViewModel

        // observa eventos para alterar o menu lateral (drawer)
        sharedViewModel.toggleDrawerEvent.observe(
            this
        ) {
            it.consume {
                if (binding.sideMenu.isDrawerOpen(Gravity.LEFT)) {
                    binding.sideMenu.closeDrawer(binding.sideMenuContent, true)
                } else {
                    binding.sideMenu.openDrawer(binding.sideMenuContent, true)
                }
            }
        }

        // observa as mudanças de erro na chamada para mostrar snackbar
        coreContext.callErrorMessageResourceId.observe(
            this
        ) {
            it.consume { message ->
                showSnackBar(message)
            }
        }

        // observa os eventos de autenticação
        authenticationRequestedEvent.observe(
            this
        ) {
            it.consume { authInfo ->
                showAuthenticationRequestedDialog(authInfo)
            }
        }

        // inicializa a atividade de assistencia se for o primeiro acesso
        if (coreContext.core.accountList.isEmpty()) {
            if (corePreferences.firstStart) {
                startActivity(Intent(this, AssistantActivity::class.java))
            }
        }

        // localiza os fragmentos na UI
        tabsFragment = findViewById(R.id.tabs_fragment)
        statusFragment = findViewById(R.id.status_fragment)

        // relata quando a UI está totalmente desenhada
        binding.root.doOnAttach {
            Log.i("[Main Activity] Report UI has been fully drawn (TTFD)")
            try {
                reportFullyDrawn()
            } catch (se: SecurityException) {
                Log.e("[Main Activity] Security exception when doing reportFullyDrawn(): $se")
            }
        }
    }

    // trata novos intents recebidos pela atividade
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        if (intent != null) {
            Log.d("[Main Activity] Found new intent")
            handleIntentParams(intent)
        }
    }

    // adiciona ouvintes aos gerenciados de contato e core
    override fun onResume() {
        super.onResume()
        coreContext.contactsManager.addListener(listener)
        coreContext.core.addListener(coreListener)
    }

    // remove os ouvintes para evitar vazamentos de memoria quando a atividade nao esta visivel
    override fun onPause() {
        coreContext.core.removeListener(coreListener)
        coreContext.contactsManager.removeListener(listener)
        super.onPause()
    }

    // mostra a snackbar com mensagem
    override fun showSnackBar(@StringRes resourceId: Int) {
        Snackbar.make(findViewById(R.id.coordinator), resourceId, Snackbar.LENGTH_LONG).show()
    }

    // snackbar com ação
    override fun showSnackBar(@StringRes resourceId: Int, action: Int, listener: () -> Unit) {
        Snackbar
            .make(findViewById(R.id.coordinator), resourceId, Snackbar.LENGTH_LONG)
            .setAction(action) {
                Log.i("[Snack Bar] Action listener triggered")
                listener()
            }
            .show()
    }

    // snackbar com mensagem
    override fun showSnackBar(message: String) {
        Snackbar.make(findViewById(R.id.coordinator), message, Snackbar.LENGTH_LONG).show()
    }

    // é chamado depois que a atividade é criada e a UI é totalmente processada
    // utilizado para registrar callbacks de componentes, adicionar listeners de mudanças de destino
    // na navegação e configurar um ouvinte de visibilidade do teclado para ajustar elementos da UI
    // inicia qualquer sobreposição necessária e trata intents que podem ter sido entregues à atividade
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        registerComponentCallbacks(componentCallbacks)
        findNavController(R.id.nav_host_fragment).addOnDestinationChangedListener(this)

        binding.rootCoordinatorLayout.setKeyboardInsetListener { keyboardVisible ->
            val portraitOrientation = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            Log.i(
                "[Main Activity] Keyboard is ${if (keyboardVisible) "visible" else "invisible"}, orientation is ${if (portraitOrientation) "portrait" else "landscape"}"
            )
            shouldTabsBeVisibleDueToOrientationAndKeyboard = !portraitOrientation || !keyboardVisible
            updateTabsFragmentVisibility()

            for (listener in keyboardVisibilityListeners) {
                listener.onKeyboardVisibilityChanged(keyboardVisible)
            }
        }

        initOverlay()

        if (intent != null) {
            Log.d("[Main Activity] Found post create intent")
            handleIntentParams(intent)
        }
    }

    // chamado quando a atividade esta sendo desmontada, limpa os listener de navegação e callbacks
    // de componentes para evitar vazamentos de memória
    override fun onDestroy() {
        findNavController(R.id.nav_host_fragment).removeOnDestinationChangedListener(this)
        unregisterComponentCallbacks(componentCallbacks)
        super.onDestroy()
    }

    // método é um ouvinte p/ mudanças de destino na navegação dentro do app, ajusta a visibilidade do 
    // teclado e de fragmentos com base no destino atual
    // serve pra responsividade
    override fun onDestinationChanged(
        controller: NavController,
        destination: NavDestination,
        arguments: Bundle?
    ) {
        hideKeyboard()
        if (statusFragment.visibility == View.GONE) {
            statusFragment.visibility = View.VISIBLE
        }

        shouldTabsBeVisibleDependingOnDestination = when (destination.id) {
            R.id.masterCallLogsFragment, R.id.masterContactsFragment, R.id.dialerFragment, R.id.masterChatRoomsFragment ->
                true
            else -> false
        }
        updateTabsFragmentVisibility()
    }

    // ouvinte a lista de keyboardvisibilitylisteners, são notificados quando a visibilidade do teclado muda
    fun addKeyboardVisibilityListener(listener: AppUtils.KeyboardVisibilityListener) {
        keyboardVisibilityListeners.add(listener)
    }

    // remove um ouvinte da lista
    fun removeKeyboardVisibilityListener(listener: AppUtils.KeyboardVisibilityListener) {
        keyboardVisibilityListeners.remove(listener)
    }

    // esconder o teclado se ouver algum componente na tela com foco
    fun hideKeyboard() {
        currentFocus?.hideKeyboard()
    }

    // mostra o teclado se houver algum componente na tela com foco
    fun showKeyboard() {
        // Requires a text field to have the focus
        if (currentFocus != null) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(currentFocus, 0)
        } else {
            Log.w("[Main Activity] Can't show the keyboard, no focused view")
        }
    }

    // controla a visibilidade do fragmento status 
    fun hideStatusFragment(hide: Boolean) {
        statusFragment.visibility = if (hide) View.GONE else View.VISIBLE
    }

    // atualiza a visibilidade das tabs com base na condição de visibilidade do destino atual e orientação
    // do dispositivo ou visibilidade do teclado
    private fun updateTabsFragmentVisibility() {
        tabsFragment.visibility = if (shouldTabsBeVisibleDependingOnDestination && shouldTabsBeVisibleDueToOrientationAndKeyboard) View.VISIBLE else View.GONE
    }

    // lida com as atividades de intents
    private fun handleIntentParams(intent: Intent) {
        Log.i(
            "[Main Activity] Handling intent with action [${intent.action}], type [${intent.type}] and data [${intent.data}]"
        )

        when (intent.action) {
            // trata o intent da main, que é lançar a activity principal sem parâmetros
            Intent.ACTION_MAIN -> handleMainIntent(intent)
            Intent.ACTION_SEND, Intent.ACTION_SENDTO -> {
                if (intent.type == "text/plain") {
                    handleSendText(intent)
                } else {
                    lifecycleScope.launch {
                        handleSendFile(intent)
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                lifecycleScope.launch {
                    handleSendMultipleFiles(intent)
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                if (uri != null) {
                    if (
                        intent.type == AppUtils.getString(R.string.linphone_address_mime_type) &&
                        PermissionHelper.get().hasReadContactsPermission()
                    ) {
                        val contactId =
                            coreContext.contactsManager.getAndroidContactIdFromUri(uri)
                        if (contactId != null) {
                            Log.i("[Main Activity] Found contact URI parameter in intent: $uri")
                            navigateToContact(contactId)
                        }
                    } else {
                        val stringUri = uri.toString()
                        if (stringUri.startsWith("linphone-config:")) {
                            val remoteConfigUri = stringUri.substring("linphone-config:".length)
                            if (corePreferences.autoRemoteProvisioningOnConfigUriHandler) {
                                Log.w(
                                    "[Main Activity] Remote provisioning URL set to [$remoteConfigUri], restarting Core now"
                                )
                                applyRemoteProvisioning(remoteConfigUri)
                            } else {
                                Log.i(
                                    "[Main Activity] Remote provisioning URL found [$remoteConfigUri], asking for user validation"
                                )
                                showAcceptRemoteConfigurationDialog(remoteConfigUri)
                            }
                        } else {
                            handleTelOrSipUri(uri)
                        }
                    }
                }
            }
            Intent.ACTION_DIAL, Intent.ACTION_CALL -> {
                val uri = intent.data
                if (uri != null) {
                    handleTelOrSipUri(uri)
                }
            }
            Intent.ACTION_VIEW_LOCUS -> {
                if (corePreferences.disableChat) return
                val locus = Compatibility.extractLocusIdFromIntent(intent)
                if (locus != null) {
                    Log.i("[Main Activity] Found chat room locus intent extra: $locus")
                    handleLocusOrShortcut(locus)
                }
            }
            else -> handleMainIntent(intent)
        }

        // Prevent this intent to be processed again
        intent.action = null
        intent.data = null
        val extras = intent.extras
        if (extras != null) {
            for (key in extras.keySet()) {
                intent.removeExtra(key)
            }
        }
    }

    private fun handleMainIntent(intent: Intent) {
        when {
            intent.hasExtra("ContactId") -> {
                val id = intent.getStringExtra("ContactId")
                Log.i("[Main Activity] Found contact ID in extras: $id")
                navigateToContact(id)
            }
            intent.hasExtra("Chat") -> {
                if (corePreferences.disableChat) return

                if (intent.hasExtra("RemoteSipUri") && intent.hasExtra("LocalSipUri")) {
                    val peerAddress = intent.getStringExtra("RemoteSipUri")
                    val localAddress = intent.getStringExtra("LocalSipUri")
                    Log.i(
                        "[Main Activity] Found chat room intent extra: local SIP URI=[$localAddress], peer SIP URI=[$peerAddress]"
                    )
                    navigateToChatRoom(localAddress, peerAddress)
                } else {
                    Log.i("[Main Activity] Found chat intent extra, go to chat rooms list")
                    navigateToChatRooms()
                }
            }
            intent.hasExtra("Dialer") -> {
                Log.i("[Main Activity] Found dialer intent extra, go to dialer")
                val isTransfer = intent.getBooleanExtra("Transfer", false)
                sharedViewModel.pendingCallTransfer = isTransfer
                navigateToDialer()
            }
            intent.hasExtra("Contacts") -> {
                Log.i("[Main Activity] Found contacts intent extra, go to contacts list")
                val isTransfer = intent.getBooleanExtra("Transfer", false)
                sharedViewModel.pendingCallTransfer = isTransfer
                navigateToContacts()
            }
            else -> {
                val core = coreContext.core
                val call = core.currentCall ?: core.calls.firstOrNull()
                if (call != null) {
                    Log.i(
                        "[Main Activity] Launcher clicked while there is at least one active call, go to CallActivity"
                    )
                    val callIntent = Intent(
                        this,
                        org.linphone.activities.voip.CallActivity::class.java
                    )
                    callIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                    startActivity(callIntent)
                }
            }
        }
    }

    private fun handleTelOrSipUri(uri: Uri) {
        Log.i("[Main Activity] Found uri: $uri to call")
        val stringUri = uri.toString()
        var addressToCall: String = stringUri

        when {
            addressToCall.startsWith("tel:") -> {
                Log.i("[Main Activity] Removing tel: prefix")
                addressToCall = addressToCall.substring("tel:".length)
            }
            addressToCall.startsWith("linphone:") -> {
                Log.i("[Main Activity] Removing linphone: prefix")
                addressToCall = addressToCall.substring("linphone:".length)
            }
            addressToCall.startsWith("sip-linphone:") -> {
                Log.i("[Main Activity] Removing linphone: sip-linphone")
                addressToCall = addressToCall.substring("sip-linphone:".length)
            }
        }

        addressToCall = addressToCall.replace("%40", "@")

        val address = coreContext.core.interpretUrl(
            addressToCall,
            LinphoneUtils.applyInternationalPrefix()
        )
        if (address != null) {
            addressToCall = address.asStringUriOnly()
        }

        Log.i("[Main Activity] Starting dialer with pre-filled URI $addressToCall")
        val args = Bundle()
        args.putString("URI", addressToCall)
        navigateToDialer(args)
    }

    private fun handleSendText(intent: Intent) {
        if (corePreferences.disableChat) return

        intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
            sharedViewModel.textToShare.value = it
        }

        handleSendChatRoom(intent)
    }

    private suspend fun handleSendFile(intent: Intent) {
        if (corePreferences.disableChat) return

        Log.i("[Main Activity] Found single file to share with type ${intent.type}")

        (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let {
            val list = arrayListOf<String>()
            coroutineScope {
                val deferred = async {
                    FileUtils.getFilePath(this@MainActivity, it)
                }
                val path = deferred.await()
                if (path != null) {
                    list.add(path)
                    Log.i("[Main Activity] Found single file to share: $path")
                }
            }
            sharedViewModel.filesToShare.value = list
        }

        // Check that the current fragment hasn't already handled the event on filesToShare
        // If it has, don't go further.
        // For example this may happen when picking a GIF from the keyboard while inside a chat room
        if (!sharedViewModel.filesToShare.value.isNullOrEmpty()) {
            handleSendChatRoom(intent)
        }
    }

    private suspend fun handleSendMultipleFiles(intent: Intent) {
        if (corePreferences.disableChat) return

        intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)?.let {
            val list = arrayListOf<String>()
            coroutineScope {
                val deferred = arrayListOf<Deferred<String?>>()
                for (parcelable in it) {
                    val uri = parcelable as Uri
                    deferred.add(async { FileUtils.getFilePath(this@MainActivity, uri) })
                }
                val paths = deferred.awaitAll()
                for (path in paths) {
                    Log.i("[Main Activity] Found file to share: $path")
                    if (path != null) list.add(path)
                }
            }
            sharedViewModel.filesToShare.value = list
        }

        handleSendChatRoom(intent)
    }

    private fun handleSendChatRoom(intent: Intent) {
        if (corePreferences.disableChat) return

        val uri = intent.data
        if (uri != null) {
            Log.i("[Main Activity] Found uri: $uri to send a message to")
            val stringUri = uri.toString()
            var addressToIM: String = stringUri
            try {
                addressToIM = URLDecoder.decode(stringUri, "UTF-8")
            } catch (e: UnsupportedEncodingException) {
                Log.e("[Main Activity] UnsupportedEncodingException: $e")
            }

            when {
                addressToIM.startsWith("sms:") ->
                    addressToIM = addressToIM.substring("sms:".length)
                addressToIM.startsWith("smsto:") ->
                    addressToIM = addressToIM.substring("smsto:".length)
                addressToIM.startsWith("mms:") ->
                    addressToIM = addressToIM.substring("mms:".length)
                addressToIM.startsWith("mmsto:") ->
                    addressToIM = addressToIM.substring("mmsto:".length)
            }

            val localAddress =
                coreContext.core.defaultAccount?.params?.identityAddress?.asStringUriOnly()
            val peerAddress = coreContext.core.interpretUrl(
                addressToIM,
                LinphoneUtils.applyInternationalPrefix()
            )?.asStringUriOnly()
            Log.i(
                "[Main Activity] Navigating to chat room with local [$localAddress] and peer [$peerAddress] addresses"
            )
            navigateToChatRoom(localAddress, peerAddress)
        } else {
            val shortcutId = intent.getStringExtra("android.intent.extra.shortcut.ID") // Intent.EXTRA_SHORTCUT_ID
            if (shortcutId != null) {
                Log.i("[Main Activity] Found shortcut ID: $shortcutId")
                handleLocusOrShortcut(shortcutId)
            } else {
                Log.i("[Main Activity] Going into chat rooms list")
                navigateToChatRooms()
            }
        }
    }

    private fun handleLocusOrShortcut(id: String) {
        val split = id.split("~")
        if (split.size == 2) {
            val localAddress = split[0]
            val peerAddress = split[1]
            Log.i(
                "[Main Activity] Navigating to chat room with local [$localAddress] and peer [$peerAddress] addresses, computed from shortcut/locus id"
            )
            navigateToChatRoom(localAddress, peerAddress)
        } else {
            Log.e(
                "[Main Activity] Failed to parse shortcut/locus id: $id, going to chat rooms list"
            )
            navigateToChatRooms()
        }
    }

    private fun initOverlay() {
        overlay = binding.root.findViewById(R.id.call_overlay)
        val callOverlay = overlay
        callOverlay ?: return

        callOverlay.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    overlayX = view.x - event.rawX
                    overlayY = view.y - event.rawY
                    initPosX = view.x
                    initPosY = view.y
                }
                MotionEvent.ACTION_MOVE -> {
                    view.animate()
                        .x(event.rawX + overlayX)
                        .y(event.rawY + overlayY)
                        .setDuration(0)
                        .start()
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(initPosX - view.x) < CorePreferences.OVERLAY_CLICK_SENSITIVITY &&
                        abs(initPosY - view.y) < CorePreferences.OVERLAY_CLICK_SENSITIVITY
                    ) {
                        view.performClick()
                    }
                }
                else -> return@setOnTouchListener false
            }
            true
        }

        callOverlay.setOnClickListener {
            coreContext.onCallOverlayClick()
        }
    }

    private fun applyRemoteProvisioning(remoteConfigUri: String) {
        coreContext.core.provisioningUri = remoteConfigUri
        coreContext.core.stop()
        coreContext.core.start()
    }

    private fun showAcceptRemoteConfigurationDialog(remoteConfigUri: String) {
        val dialogViewModel = DialogViewModel(
            remoteConfigUri,
            getString(R.string.dialog_apply_remote_provisioning_title)
        )
        val dialog = DialogUtils.getDialog(this, dialogViewModel)

        dialogViewModel.showCancelButton {
            Log.i("[Main Activity] User cancelled remote provisioning config")
            dialog.dismiss()
        }

        val okLabel = getString(
            R.string.dialog_apply_remote_provisioning_button
        )
        dialogViewModel.showOkButton(
            {
                Log.w(
                    "[Main Activity] Remote provisioning URL set to [$remoteConfigUri], restarting Core now"
                )
                applyRemoteProvisioning(remoteConfigUri)
                dialog.dismiss()
            },
            okLabel
        )

        dialog.show()
    }

    private fun showAuthenticationRequestedDialog(
        authInfo: AuthInfo
    ) {
        authenticationRequiredDialog?.dismiss()

        val accountFound = coreContext.core.accountList.find {
            it.params.identityAddress?.username == authInfo.username && it.params.identityAddress?.domain == authInfo.domain
        }
        if (accountFound == null) {
            Log.w("[Main Activity] Failed to find account matching auth info, aborting auth dialog")
            return
        }

        val identity = "${authInfo.username}@${authInfo.domain}"
        Log.i("[Main Activity] Showing authentication required dialog for account [$identity]")

        val dialogViewModel = DialogViewModel(
            getString(R.string.dialog_authentication_required_message, identity),
            getString(R.string.dialog_authentication_required_title)
        )
        dialogViewModel.showPassword = true
        dialogViewModel.passwordTitle = getString(
            R.string.settings_password_protection_dialog_input_hint
        )
        val dialog = DialogUtils.getDialog(this, dialogViewModel)

        dialogViewModel.showCancelButton {
            dialog.dismiss()
            authenticationRequiredDialog = null
        }

        dialogViewModel.showOkButton(
            {
                Log.i(
                    "[Main Activity] Updating password for account [$identity] using auth info [$authInfo]"
                )
                val newPassword = dialogViewModel.password
                authInfo.password = newPassword
                coreContext.core.addAuthInfo(authInfo)

                coreContext.core.refreshRegisters()

                dialog.dismiss()
                authenticationRequiredDialog = null
            },
            getString(R.string.dialog_authentication_required_change_password_label)
        )

        dialog.show()
        authenticationRequiredDialog = dialog
    }
}
