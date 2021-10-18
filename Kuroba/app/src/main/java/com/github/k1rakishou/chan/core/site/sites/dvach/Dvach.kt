package com.github.k1rakishou.chan.core.site.sites.dvach

import com.github.k1rakishou.OptionSettingItem
import com.github.k1rakishou.Setting
import com.github.k1rakishou.chan.core.net.JsonReaderRequest
import com.github.k1rakishou.chan.core.site.ChunkDownloaderSiteProperties
import com.github.k1rakishou.chan.core.site.Site
import com.github.k1rakishou.chan.core.site.Site.BoardsType
import com.github.k1rakishou.chan.core.site.Site.SiteFeature
import com.github.k1rakishou.chan.core.site.SiteActions
import com.github.k1rakishou.chan.core.site.SiteAuthentication
import com.github.k1rakishou.chan.core.site.SiteIcon
import com.github.k1rakishou.chan.core.site.SiteRequestModifier
import com.github.k1rakishou.chan.core.site.SiteSetting
import com.github.k1rakishou.chan.core.site.SiteSetting.SiteOptionsSetting
import com.github.k1rakishou.chan.core.site.common.CommonSite
import com.github.k1rakishou.chan.core.site.common.MultipartHttpCall
import com.github.k1rakishou.chan.core.site.common.vichan.VichanActions
import com.github.k1rakishou.chan.core.site.common.vichan.VichanEndpoints
import com.github.k1rakishou.chan.core.site.http.DeleteRequest
import com.github.k1rakishou.chan.core.site.http.HttpCall
import com.github.k1rakishou.chan.core.site.http.login.AbstractLoginRequest
import com.github.k1rakishou.chan.core.site.http.login.DvachLoginRequest
import com.github.k1rakishou.chan.core.site.http.login.DvachLoginResponse
import com.github.k1rakishou.chan.core.site.limitations.PasscodeDependantMaxAttachablesTotalSize
import com.github.k1rakishou.chan.core.site.limitations.PasscodePostingLimitationsInfo
import com.github.k1rakishou.chan.core.site.limitations.SiteDependantAttachablesCount
import com.github.k1rakishou.chan.core.site.limitations.SitePostingLimitationInfo
import com.github.k1rakishou.chan.core.site.parser.CommentParser
import com.github.k1rakishou.chan.core.site.parser.CommentParserType
import com.github.k1rakishou.chan.core.site.sites.search.DvachSearchParams
import com.github.k1rakishou.chan.core.site.sites.search.SearchParams
import com.github.k1rakishou.chan.core.site.sites.search.SearchResult
import com.github.k1rakishou.chan.core.site.sites.search.SiteGlobalSearchType
import com.github.k1rakishou.common.AppConstants
import com.github.k1rakishou.common.DoNotStrip
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.common.appendCookieHeader
import com.github.k1rakishou.common.errorMessageOrClassName
import com.github.k1rakishou.model.data.board.ChanBoard
import com.github.k1rakishou.model.data.board.pages.BoardPages
import com.github.k1rakishou.model.data.descriptor.BoardDescriptor
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.descriptor.PostDescriptor
import com.github.k1rakishou.model.data.descriptor.SiteDescriptor
import com.github.k1rakishou.model.data.site.SiteBoards
import com.github.k1rakishou.persist_state.ReplyMode
import com.github.k1rakishou.prefs.JsonSetting
import com.github.k1rakishou.prefs.OptionsSetting
import com.github.k1rakishou.prefs.StringSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.*

@DoNotStrip
class Dvach : CommonSite() {
  private val chunkDownloaderSiteProperties = ChunkDownloaderSiteProperties(
    enabled = true,
    // 2ch.hk sends file size in KB
    siteSendsCorrectFileSizeInBytes = false
  )

  private lateinit var passCode: StringSetting
  private lateinit var passCookie: StringSetting
  private lateinit var userCodeCookie: StringSetting
  private lateinit var antiSpamCookie: StringSetting
  private lateinit var captchaType: OptionsSetting<CaptchaType>
  private lateinit var passCodeInfo: JsonSetting<DvachPasscodeInfo>

  private val siteRequestModifier by lazy { DvachSiteRequestModifier(this, appConstants) }

  val captchaV2NoJs = SiteAuthentication.fromCaptcha2nojs(
    NORMAL_CAPTCHA_KEY,
    "https://2ch.hk/api/captcha/recaptcha/mobile"
  )

  val captchaV2Js = SiteAuthentication.fromCaptcha2(
    NORMAL_CAPTCHA_KEY,
    "https://2ch.hk/api/captcha/recaptcha/mobile"
  )

  val captchaV2Invisible = SiteAuthentication.fromCaptcha2Invisible(
    INVISIBLE_CAPTCHA_KEY,
    "https://2ch.hk/api/captcha/invisible_recaptcha/mobile"
  )

  val dvachCaptcha = SiteAuthentication.idBased(
    "https://2ch.hk/api/captcha/2chcaptcha/id"
  )

  override fun initialize() {
    super.initialize()

    passCode = StringSetting(prefs, "preference_pass_code", "")
    passCookie = StringSetting(prefs, "preference_pass_cookie", "")
    userCodeCookie = StringSetting(prefs, "user_code_cookie", "")
    antiSpamCookie = StringSetting(prefs, "dvach_anti_spam_cookie", "")

    captchaType = OptionsSetting(
      prefs,
      "preference_captcha_type_dvach",
      CaptchaType::class.java,
      CaptchaType.DVACH_CAPTCHA
    )

    passCodeInfo = JsonSetting(
      gson,
      DvachPasscodeInfo::class.java,
      prefs,
      "preference_pass_code_info",
      DvachPasscodeInfo()
    )
  }

  override fun settings(): List<SiteSetting> {
    val settings = ArrayList<SiteSetting>()

    settings.addAll(super.settings())

    settings.add(SiteOptionsSetting("Captcha type", null, captchaType, mutableListOf("Javascript", "Noscript", "Invisible")))
    settings.add(SiteSetting.SiteStringSetting("User code cookie", null, userCodeCookie))
    settings.add(SiteSetting.SiteStringSetting("Anti-spam cookie", null, antiSpamCookie))

    return settings
  }

  override fun <T : Setting<*>> getSettingBySettingId(settingId: SiteSetting.SiteSettingId): T? {
    return when (settingId) {
      // Used for hidden boards accessing
      SiteSetting.SiteSettingId.DvachUserCodeCookie -> userCodeCookie as T
      SiteSetting.SiteSettingId.DvachAntiSpamCookie -> antiSpamCookie as T
      else -> super.getSettingBySettingId(settingId)
    }
  }

  override fun siteGlobalSearchType(): SiteGlobalSearchType = SiteGlobalSearchType.SimpleQueryBoardSearch

  override fun setParser(commentParser: CommentParser) {
    postParser = DvachPostParser(commentParser, archivesManager)
  }

  override fun setup() {
    setEnabled(true)
    setName(SITE_NAME)
    setIcon(SiteIcon.fromFavicon(imageLoaderV2, "https://2ch.hk/favicon.ico".toHttpUrl()))
    setBoardsType(BoardsType.DYNAMIC)
    setResolvable(URL_HANDLER)
    setConfig(object : CommonConfig() {
      override fun siteFeature(siteFeature: SiteFeature): Boolean {
        return super.siteFeature(siteFeature)
          || siteFeature == SiteFeature.POSTING
          || siteFeature == SiteFeature.LOGIN
      }
    })
    setEndpoints(DvachEndpoints(this))
    setActions(object : VichanActions(this@Dvach, proxiedOkHttpClient, siteManager, replyManager) {
      override fun setupPost(
        replyChanDescriptor: ChanDescriptor,
        call: MultipartHttpCall
      ): ModularResult<Unit> {
        return super.setupPost(replyChanDescriptor, call)
          .mapValue {
            if (replyChanDescriptor.isThreadDescriptor()) {
              // "thread" is already added in VichanActions.
              call.parameter("post", "New Reply")
            } else {
              call.parameter("post", "New Thread")
              call.parameter("page", "1")
            }

            return@mapValue
          }
      }

      override fun requirePrepare(): Boolean {
        return false
      }

      override suspend fun post(
        replyChanDescriptor: ChanDescriptor,
        replyMode: ReplyMode
      ): Flow<SiteActions.PostResult> {
        val replyCall = DvachReplyCall(
          site = this@Dvach,
          replyChanDescriptor = replyChanDescriptor,
          replyMode = replyMode,
          replyManager = replyManager
        )

        return httpCallManager.get().makePostHttpCallWithProgress(replyCall)
          .map { replyCallResult ->
            when (replyCallResult) {
              is HttpCall.HttpCallWithProgressResult.Success -> {
                return@map SiteActions.PostResult.PostComplete(
                  replyCallResult.httpCall.replyResponse
                )
              }
              is HttpCall.HttpCallWithProgressResult.Progress -> {
                return@map SiteActions.PostResult.UploadingProgress(
                  replyCallResult.fileIndex,
                  replyCallResult.totalFiles,
                  replyCallResult.percent
                )
              }
              is HttpCall.HttpCallWithProgressResult.Fail -> {
                return@map SiteActions.PostResult.PostError(replyCallResult.error)
              }
            }
          }
      }

      override suspend fun delete(deleteRequest: DeleteRequest): SiteActions.DeleteResult {
        return super.delete(deleteRequest)
      }

      override suspend fun boards(): ModularResult<SiteBoards> {
        val dvachEndpoints = endpoints() as DvachEndpoints

        return DvachBoardsRequest(
          siteDescriptor(),
          boardManager,
          proxiedOkHttpClient,
          dvachEndpoints.boards(),
          dvachEndpoints.dvachGetBoards()
        ).execute()
      }

      @Suppress("MoveVariableDeclarationIntoWhen")
      override suspend fun <T : AbstractLoginRequest> login(loginRequest: T): SiteActions.LoginResult {
        val dvachLoginRequest = loginRequest as DvachLoginRequest
        passCode.set(dvachLoginRequest.passcode)

        val loginResult = httpCallManager.get().makeHttpCall(
          DvachGetPassCookieHttpCall(this@Dvach, loginRequest)
        )

        when (loginResult) {
          is HttpCall.HttpCallResult.Success -> {
            val loginResponse =
              requireNotNull(loginResult.httpCall.loginResponse) { "loginResponse is null" }

            return when (loginResponse) {
              is DvachLoginResponse.Success -> {
                passCookie.set(loginResponse.authCookie)
                SiteActions.LoginResult.LoginComplete(loginResponse)
              }
              is DvachLoginResponse.Failure -> {
                SiteActions.LoginResult.LoginError(loginResponse.errorMessage)
              }
              DvachLoginResponse.AntiSpamDetected -> {
                SiteActions.LoginResult.AntiSpamDetected
              }
            }
          }
          is HttpCall.HttpCallResult.Fail -> {
            return SiteActions.LoginResult.LoginError(loginResult.error.errorMessageOrClassName())
          }
        }
      }

      override suspend fun getOrRefreshPasscodeInfo(resetCached: Boolean): SiteActions.GetPasscodeInfoResult {
        if (!isLoggedIn()) {
          return SiteActions.GetPasscodeInfoResult.NotLoggedIn
        }

        if (!resetCached && passCodeInfo.isNotDefault()) {
          val dvachPasscodeInfo = passCodeInfo.get()

          val maxAttachedFilesPerPost = dvachPasscodeInfo.files
          val maxTotalAttachablesSize = dvachPasscodeInfo.filesSize

          if (maxAttachedFilesPerPost != null && maxTotalAttachablesSize != null) {
            val passcodePostingLimitationsInfo = PasscodePostingLimitationsInfo(
              maxAttachedFilesPerPost,
              maxTotalAttachablesSize
            )

            return SiteActions.GetPasscodeInfoResult.Success(passcodePostingLimitationsInfo)
          }

          // fallthrough
        }

        val passcodeInfoCall = DvachGetPasscodeInfoHttpCall(this@Dvach, gson)

        val passcodeInfoCallResult = httpCallManager.get().makeHttpCall(passcodeInfoCall)
        if (passcodeInfoCallResult is HttpCall.HttpCallResult.Fail) {
          return SiteActions.GetPasscodeInfoResult.Failure(passcodeInfoCallResult.error)
        }

        val passcodePostingLimitationsInfoResult = (passcodeInfoCallResult as HttpCall.HttpCallResult.Success)
          .httpCall.passcodePostingLimitationsInfoResult

        if (passcodePostingLimitationsInfoResult is ModularResult.Error) {
          return SiteActions.GetPasscodeInfoResult.Failure(passcodePostingLimitationsInfoResult.error)
        }

        val passcodePostingLimitationsInfo =
          (passcodePostingLimitationsInfoResult as ModularResult.Value).value

        val dvachPasscodeInfo = DvachPasscodeInfo(
          files = passcodePostingLimitationsInfo.maxAttachedFilesPerPost,
          filesSize = passcodePostingLimitationsInfo.maxTotalAttachablesSize
        )

        passCodeInfo.set(dvachPasscodeInfo)

        return SiteActions.GetPasscodeInfoResult.Success(passcodePostingLimitationsInfo)
      }

      override fun postRequiresAuthentication(): Boolean {
        return !isLoggedIn()
      }

      override fun postAuthenticate(): SiteAuthentication {
        return when (captchaType.get()) {
          CaptchaType.V2JS -> captchaV2Js
          CaptchaType.V2NOJS -> captchaV2NoJs
          CaptchaType.V2_INVISIBLE -> captchaV2Invisible
          CaptchaType.DVACH_CAPTCHA -> dvachCaptcha
          else -> throw IllegalArgumentException()
        }
      }

      override fun logout() {
        passCode.remove()
        passCookie.remove()
        passCodeInfo.reset()
      }

      override fun isLoggedIn(): Boolean {
        return passCookie.get().isNotEmpty()
      }

      override fun loginDetails(): DvachLoginRequest {
        return DvachLoginRequest(passCode.get())
      }

      override suspend fun pages(
        board: ChanBoard
      ): JsonReaderRequest.JsonReaderResponse<BoardPages> {
        val request = Request.Builder()
          .url(endpoints().pages(board))
          .get()
          .build()

        return DvachPagesRequest(
          board,
          request,
          proxiedOkHttpClient
        ).execute()
      }

      override suspend fun <T : SearchParams> search(searchParams: T): SearchResult {
        val dvachSearchParams = searchParams as DvachSearchParams

        // https://2ch.hk/makaba/makaba.fcgi?task=search&board=mobi&find=poco%20x3&json=1
        val searchUrl = requireNotNull(endpoints().search())
          .newBuilder()
          .addQueryParameter("board", dvachSearchParams.boardCode)
          .addQueryParameter("find", dvachSearchParams.query)
          .addQueryParameter("json", "1")
          .build()

        val requestBuilder = Request.Builder()
          .url(searchUrl)
          .get()

        this@Dvach.requestModifier().modifySearchGetRequest(this@Dvach, requestBuilder)

        return DvachSearchRequest(
          moshi,
          requestBuilder.build(),
          proxiedOkHttpClient,
          dvachSearchParams,
          siteManager
        ).execute()
      }
    })
    setRequestModifier(siteRequestModifier as SiteRequestModifier<Site>)
    setApi(DvachApiV2(moshi, siteManager, boardManager, this))
    setParser(DvachCommentParser())

    setPostingLimitationInfo(
      SitePostingLimitationInfo(
        postMaxAttachables = SiteDependantAttachablesCount(siteManager, 4),
        postMaxAttachablesTotalSize = PasscodeDependantMaxAttachablesTotalSize(
          siteManager = siteManager
        )
      )
    )
  }

  override fun commentParserType(): CommentParserType {
    return CommentParserType.DvachParser
  }

  override fun getChunkDownloaderSiteProperties(): ChunkDownloaderSiteProperties {
    return chunkDownloaderSiteProperties
  }

  override fun redirectsToArchiveThread(): Boolean = true

  inner class DvachEndpoints(commonSite: CommonSite) : VichanEndpoints(
    commonSite,
    URL_HANDLER.url!!.toString(),
    URL_HANDLER.url!!.toString()
  ) {
    override fun imageUrl(boardDescriptor: BoardDescriptor, arg: Map<String, String>): HttpUrl {
      val path = requireNotNull(arg["path"]) { "\"path\" parameter not found" }

      return root.builder().s(path).url()
    }

    override fun thumbnailUrl(
      boardDescriptor: BoardDescriptor,
      spoiler: Boolean,
      customSpoilers: Int,
      arg: Map<String, String>
    ): HttpUrl {
      val thumbnail = requireNotNull(arg["thumbnail"]) { "\"thumbnail\" parameter not found" }

      return root.builder().s(thumbnail).url()
    }

    fun dvachGetBoards(): HttpUrl {
      return HttpUrl.Builder()
        .scheme("https")
        .host(URL_HANDLER.url!!.host)
        .addPathSegment("makaba")
        .addPathSegment("mobile.fcgi")
        .addQueryParameter("task", "get_boards")
        .build()
    }

    override fun boards(): HttpUrl {
      return HttpUrl.Builder()
        .scheme("https")
        .host(URL_HANDLER.url!!.host)
        .addPathSegment("boards.json")
        .build()
    }

    // /api/mobile/v2/after/{board}/{thread}/{num}
    override fun threadPartial(fromPostDescriptor: PostDescriptor): HttpUrl {
      return HttpUrl.Builder()
        .scheme("https")
        .host(URL_HANDLER.url!!.host)
        .addPathSegment("api")
        .addPathSegment("mobile")
        .addPathSegment("v2")
        .addPathSegment("after")
        .addPathSegment(fromPostDescriptor.boardDescriptor().boardCode)
        .addPathSegment(fromPostDescriptor.getThreadNo().toString())
        .addPathSegment(fromPostDescriptor.postNo.toString())
        .build()
    }

    // https://2ch.hk/board_code/arch/res/thread_no.json
    override fun threadArchive(threadDescriptor: ChanDescriptor.ThreadDescriptor): HttpUrl {
      return HttpUrl.Builder()
        .scheme("https")
        .host(URL_HANDLER.url!!.host)
        .addPathSegment(threadDescriptor.boardCode())
        .addPathSegment("arch")
        .addPathSegment("res")
        .addPathSegment("${threadDescriptor.threadNo}.json")
        .build()
    }

    override fun pages(board: ChanBoard): HttpUrl {
      return HttpUrl.Builder()
        .scheme("https")
        .host(URL_HANDLER.url!!.host)
        .addPathSegment(board.boardCode())
        .addPathSegment("catalog.json")
        .build()
    }

    override fun reply(chanDescriptor: ChanDescriptor): HttpUrl {
      return HttpUrl.Builder()
        .scheme("https")
        .host(URL_HANDLER.url!!.host)
        .addPathSegment("makaba")
        .addPathSegment("posting.fcgi")
        .addQueryParameter("json", "1")
        .build()
    }

    override fun login(): HttpUrl {
      return HttpUrl.Builder()
        .scheme("https")
        .host(URL_HANDLER.url!!.host)
        .addPathSegment("makaba")
        .addPathSegment("makaba.fcgi")
        .build()
    }

    override fun passCodeInfo(): HttpUrl? {
      if (!actions().isLoggedIn()) {
        return null
      }

      val passcode = passCode.get()
      if (passcode.isEmpty()) {
        return null
      }

      return HttpUrl.Builder()
        .scheme("https")
        .host(URL_HANDLER.url!!.host)
        .addPathSegment("makaba")
        .addPathSegment("makaba.fcgi")
        .addQueryParameter("task", "auth")
        .addQueryParameter("usercode", passcode)
        .addQueryParameter("json", "1")
        .build()
    }

    override fun search(): HttpUrl {
      return HttpUrl.Builder()
        .scheme("https")
        .host(URL_HANDLER.url!!.host)
        .addPathSegment("makaba")
        .addPathSegment("makaba.fcgi")
        .addQueryParameter("task", "search")
        .build()
    }
  }

  class DvachSiteRequestModifier(
    site: Dvach,
    appConstants: AppConstants
  ) : SiteRequestModifier<Dvach>(site, appConstants) {

    override fun modifyHttpCall(httpCall: HttpCall, requestBuilder: Request.Builder) {
      super.modifyHttpCall(httpCall, requestBuilder)

      if (site.actions().isLoggedIn()) {
        requestBuilder.appendCookieHeader("passcode_auth=" + site.passCookie.get())
      }

      addAntiSpamCookie(requestBuilder)
      addUserCodeCookie(site, requestBuilder)
    }

    override fun modifyThumbnailGetRequest(site: Dvach, requestBuilder: Request.Builder) {
      super.modifyThumbnailGetRequest(site, requestBuilder)

      addAntiSpamCookie(requestBuilder)
      addUserCodeCookie(site, requestBuilder)
    }

    override fun modifyCatalogOrThreadGetRequest(
      site: Dvach,
      chanDescriptor: ChanDescriptor,
      requestBuilder: Request.Builder
    ) {
      super.modifyCatalogOrThreadGetRequest(site, chanDescriptor, requestBuilder)

      addAntiSpamCookie(requestBuilder)
      addUserCodeCookie(site, requestBuilder)
    }

    override fun modifyFullImageHeadRequest(site: Dvach, requestBuilder: Request.Builder) {
      super.modifyFullImageHeadRequest(site, requestBuilder)

      addAntiSpamCookie(requestBuilder)
      addUserCodeCookie(site, requestBuilder)
    }

    override fun modifyFullImageGetRequest(site: Dvach, requestBuilder: Request.Builder) {
      super.modifyFullImageGetRequest(site, requestBuilder)

      addAntiSpamCookie(requestBuilder)
      addUserCodeCookie(site, requestBuilder)
    }

    override fun modifyMediaDownloadRequest(site: Dvach, requestBuilder: Request.Builder) {
      super.modifyMediaDownloadRequest(site, requestBuilder)

      addAntiSpamCookie(requestBuilder)
      addUserCodeCookie(site, requestBuilder)
    }

    override fun modifySearchGetRequest(site: Dvach, requestBuilder: Request.Builder) {
      super.modifySearchGetRequest(site, requestBuilder)

      addAntiSpamCookie(requestBuilder)
      addUserCodeCookie(site, requestBuilder)
    }

    override fun modifyVideoStreamRequest(
      site: Dvach,
      requestProperties: MutableMap<String, String>
    ) {
      super.modifyVideoStreamRequest(site, requestProperties)

      val fullCookie = buildString {
        val userCodeCookie = site.userCodeCookie.get()
        if (userCodeCookie.isNotEmpty()) {
          append("${USER_CODE_COOKIE_KEY}=${userCodeCookie};")
        }

        val antiSpamCookie = site.antiSpamCookie.get()
        if (antiSpamCookie.isNotEmpty()) {
          append(antiSpamCookie)
          append(";")
        }
      }

      requestProperties.put("Cookie", fullCookie)
    }

    override fun modifyCaptchaGetRequest(site: Dvach, requestBuilder: Request.Builder) {
      super.modifyCaptchaGetRequest(site, requestBuilder)

      addAntiSpamCookie(requestBuilder)
      addUserCodeCookie(site, requestBuilder)
    }

    private fun addUserCodeCookie(
      site: Dvach,
      requestBuilder: Request.Builder
    ) {
      val userCodeCookie = site.userCodeCookie.get()
      if (userCodeCookie.isEmpty()) {
        return
      }

      requestBuilder.appendCookieHeader("${USER_CODE_COOKIE_KEY}=${userCodeCookie}")
    }

    private fun addAntiSpamCookie(requestBuilder: Request.Builder) {
      val antiSpamCookie = site.antiSpamCookie.get()
      if (antiSpamCookie.isNotEmpty()) {
        requestBuilder.appendCookieHeader(antiSpamCookie)
      }
    }

  }

  @DoNotStrip
  enum class CaptchaType(val value: String) : OptionSettingItem {
    V2JS("v2js"),
    V2NOJS("v2nojs"),
    V2_INVISIBLE("v2_invisible"),
    DVACH_CAPTCHA("dvach_captcha");

    override fun getKey(): String {
      return value
    }

  }

  companion object {
    private const val TAG = "Dvach"
    const val SITE_NAME = "2ch.hk"
    val SITE_DESCRIPTOR = SiteDescriptor.create(SITE_NAME)
    const val NORMAL_CAPTCHA_KEY = "6LeQYz4UAAAAAL8JCk35wHSv6cuEV5PyLhI6IxsM"
    const val INVISIBLE_CAPTCHA_KEY = "6LdwXD4UAAAAAHxyTiwSMuge1-pf1ZiEL4qva_xu"
    const val DEFAULT_MAX_FILE_SIZE = 20480 * 1024 // 20MB

    const val USER_CODE_COOKIE_KEY = "usercode_auth"

    // Lmao, apparently this is the only endpoint where there is no NSFW ads and the anti-spam
    // script is working. For some reason it doesn't work on https://2ch.hk anymore, meaning opening
    // https://2ch.hk doesn't trigger anti-spam script.
    const val ANTI_SPAM_CHALLENGE_ENDPOINT = "https://2ch.hk/challenge/"

    @JvmField
    val URL_HANDLER: CommonSiteUrlHandler = object : CommonSiteUrlHandler() {
      val ROOT = "https://2ch.hk/"

      override fun getSiteClass(): Class<out Site?> {
        return Dvach::class.java
      }

      override val url: HttpUrl
        get() = ROOT.toHttpUrl()

      override val mediaHosts: Array<HttpUrl>
        get() = arrayOf(url)

      override val names: Array<String>
        get() = arrayOf("dvach", "2ch")

      override fun desktopUrl(chanDescriptor: ChanDescriptor, postNo: Long?): String? {
        when (chanDescriptor) {
          is ChanDescriptor.CatalogDescriptor -> {
            val builtUrl = url.newBuilder()
              .addPathSegment(chanDescriptor.boardCode())
              .toString()

            if (postNo == null) {
              return builtUrl
            }

            return "${builtUrl}/res/${postNo}.html"
          }
          is ChanDescriptor.ThreadDescriptor -> {
            val builtUrl = url.newBuilder()
              .addPathSegment(chanDescriptor.boardCode())
              .addPathSegment("res")
              .addPathSegment(chanDescriptor.threadNo.toString() + ".html")
              .toString()

            if (postNo == null) {
              return builtUrl
            }

            return "${builtUrl}#${postNo}"
          }
          else -> return null
        }
      }
    }
  }

}