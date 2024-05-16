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
package org.linphone

// suprimir avisos especificos do lint do android (sobre os vazamentos de memoria estática)
import android.annotation.SuppressLint
// importa a base application, que fornece métodos de gerenciamento do ciclo de vida do app
import android.app.Application
// fornece acesso ao contexto do aplicativo, uma interface para acessar recursos especificos do sistema 
// e serviço, como lançar atividade, receber intents
import android.content.Context
// usado para acessar informações sobre a vers do sistema em execução do app, acessar a vers. do Android
import android.os.Build
// indicar um método ou classe que usa APIs que precisam de uma versão especifica do android
import androidx.annotation.RequiresApi
// usado para carregar e gerenciar imagens de forma async
import coil.ImageLoader
// importa a interface do ImageLoaderFactory
import coil.ImageLoaderFactory
// importa o decodificador de GIF do coil
import coil.decode.GifDecoder
// importa um decodificador para todos os tipos de imagens
import coil.decode.ImageDecoderDecoder
// decodificador de SVG
import coil.decode.SvgDecoder
// decoficiador de quadros de video do coil, permitindo carregar frames de video como imagem
import coil.decode.VideoFrameDecoder
// gerencia o disco de cache que sao onde estao armazenadas as imagens em disco para 
// reutilização futura e desempenho
import coil.disk.DiskCache
// gerencia a memoria cache que armazena imagens na memoria ram pra acesso rapido
import coil.memory.MemoryCache
// importa as classes do linphone
import org.linphone.core.*
// importa o log do linphone
import org.linphone.core.tools.Log
// importa a classe que verifica as versões de API e outras funcionalidades relacionados ao streaming de media
// para conficionar recursos que dependem de funções específicas do Android
import org.linphone.mediastream.Version

// Classe principal da aplicação
class LinphoneApplication : Application(), ImageLoaderFactory {
    // estado principal da aplicação, define e mantem o estado global da aplicação
    companion object {
        // variáveis que são executadas depois e o suppress é uma anotação para ignorar possível vazamento
        // de memória, para manter o contexto estático em app android
        @SuppressLint("StaticFieldLeak")
        lateinit var corePreferences: CorePreferences

        @SuppressLint("StaticFieldLeak")
        lateinit var coreContext: CoreContext

        // configuração do ambiente de execução e log
        private fun createConfig(context: Context) {
            // verifica se o contexto já foi criado
            if (::corePreferences.isInitialized) {
                return
            }

            // configuração dos logs
            Factory.instance().setLogCollectionPath(context.filesDir.absolutePath)
            Factory.instance().enableLogCollection(LogCollectionState.Enabled)

            // onde os arquivos temporários de cache serão armazenados
            // For VFS
            Factory.instance().setCacheDir(context.cacheDir.absolutePath)

            // inicia uma instância do corepreferences, que gerencia as configurações
            // como os caminhos de config e ativ do VFS (cache) se necessário
            corePreferences = CorePreferences(context)
            corePreferences.copyAssetsFromPackage()

            if (corePreferences.vfsEnabled) {
                CoreContext.activateVFS()
            }

            // usa o Factory para criar a config do core com os caminhos especificados
            val config = Factory.instance().createConfigWithFactory(
                corePreferences.configPath,
                corePreferences.factoryConfigPath
            )
            corePreferences.config = config

            // configuração dos Logs adicionais
            val appName = context.getString(R.string.app_name)
            Factory.instance().setLoggerDomain(appName)
            Factory.instance().enableLogcatLogs(corePreferences.logcatLogsOutput)
            if (corePreferences.debugLogs) {
                Factory.instance().loggingService.setLogLevel(LogLevel.Message)
            }

            Log.i("[Application] Core config & preferences created")
        }

        // detalhamento do processo de verificação e criação do contexto core
        fun ensureCoreExists(
            context: Context,
            pushReceived: Boolean = false,
            service: CoreService? = null,
            useAutoStartDescription: Boolean = false,
            skipCoreStart: Boolean = false
        ): Boolean {
            // checa se o contexto ja está incializado e não parado para evitar recriação desnecessaria
            if (::coreContext.isInitialized && !coreContext.stopped) {
                Log.d("[Application] Skipping Core creation (push received? $pushReceived)")
                return false
            }

            Log.i(
                "[Application] Core context is being created ${if (pushReceived) "from push" else ""}"
            )
            coreContext = CoreContext(
                context,
                corePreferences.config,
                service,
                useAutoStartDescription
            )
            if (!skipCoreStart) {
                coreContext.start()
            }
            return true
        }
        // verifica se o contexto core está inicializado
        fun contextExists(): Boolean {
            return ::coreContext.isInitialized
        }
    }

    // inicia o ambiente da aplicação
    override fun onCreate() {
        super.onCreate()
        val appName = getString(R.string.app_name)
        android.util.Log.i("[$appName]", "Application is being created")
        createConfig(applicationContext)
        Log.i("[Application] Created")
    }

    // cria uma instancia do ImageLoader para o app
    @RequiresApi(Build.VERSION_CODES.P)
    override fun newImageLoader(): ImageLoader {
        // cria um novo construtor de ImageLoader, passando a instância atual da aplicação como contexto
        return ImageLoader.Builder(this)
        // configuração de componentes
            .components {
                // adiciona suporte para decoficar frames de video
                add(VideoFrameDecoder.Factory())
                // suporte pra decodificar SVG
                add(SvgDecoder.Factory())
                // suporte pra decodificar imagens GIF e SVG no Android 9.0+ (API 28) e acima
                if (Version.sdkAboveOrEqual(Version.API28_PIE_90)) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    // usado em pre pie (vers Android) para decoficiar GIF
                    add(GifDecoder.Factory())
                }
            }
            .memoryCache {
                // configura o cache para memoria de imagens, armazena imagens decoficiadas na RAM 
                // pra acesso rapido
                // limita o cache em 25% da memoria disponivel do app
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                // configura o cache de disco para imagens, armazena imagens decoficiadas em disco
                // especifica onde as imagens serao armazenadas
                // define o cache de disco a 2% do espaço disponivel no disco
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            // finaliza a configuração e cria a instancia com as configurações necessárias
            .build()
    }
}
