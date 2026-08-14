package com.tinhcd.myesalessfa.data.remote

import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PATCH
import retrofit2.http.Query

/**
 * Every data call the app makes, as PostgREST sees it.
 *
 * PostgREST is not a hand-written API: the query lives in the query string, so
 * `select` lists, `eq.`/`neq.`/`cs.` operators and `order` clauses are all values
 * passed in rather than paths. Keeping them as named constants in [Selects] and
 * [Filters] rather than inline strings is what stops a typo in a column list from
 * becoming a silent missing field at decode time.
 *
 * Reads return decoded lists and let Retrofit throw on failure. Writes return
 * `Response<Unit>` because PostgREST answers them with an empty body, and are
 * checked with `orThrow()` so the error message PostgREST supplies survives.
 */
interface PostgrestService {

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    /** RLS narrows this to the signed-in rep, so it needs no filter. */
    @GET("salesperson")
    suspend fun salesperson(
        @Query("select") select: String = Selects.SALESPERSON,
    ): List<SalespersonDto>

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    @GET("app_setting")
    suspend fun settings(
        @Query("select") select: String = Selects.SETTING,
    ): List<SettingDto>

    @GET("reason_code")
    suspend fun reasonCodes(
        @Query("select") select: String = Selects.REASON_CODE,
        @Query("is_active") isActive: String = Filters.IS_TRUE,
    ): List<ReasonCodeDto>

    @GET("sales_step")
    suspend fun salesSteps(
        @Query("select") select: String = Selects.SALES_STEP,
        @Query("is_active") isActive: String = Filters.IS_TRUE,
    ): List<SalesStepDto>

    @GET("translation")
    suspend fun translations(
        @Query("lang_code") langCode: String,
        @Query("select") select: String = Selects.TRANSLATION,
    ): List<TranslationDto>

    // -------------------------------------------------------------------------
    // Route
    // -------------------------------------------------------------------------

    /**
     * @param visitWeekdays array-contains, e.g. `cs.{5}` for Friday. Retrofit
     * percent-encodes the braces.
     */
    @GET("route_customer")
    suspend fun routeCustomers(
        @Query("visit_weekdays") visitWeekdays: String,
        @Query("select") select: String = Selects.ROUTE_CUSTOMER,
        @Query("is_active") isActive: String = Filters.IS_TRUE,
        @Query("order") order: String = "visit_order.asc",
    ): List<RouteCustomerDto>

    @GET("visit")
    suspend fun visits(
        @Query("visit_date") visitDate: String,
        @Query("select") select: String = Selects.VISIT,
    ): List<VisitDto>

    // -------------------------------------------------------------------------
    // Catalogue
    // -------------------------------------------------------------------------

    @GET("product")
    suspend fun products(
        @Query("select") select: String = Selects.PRODUCT,
        @Query("is_active") isActive: String = Filters.IS_TRUE,
    ): List<ProductDto>

    @GET("product_uom")
    suspend fun productUoms(
        @Query("select") select: String = Selects.PRODUCT_UOM,
    ): List<ProductUomDto>

    /** RLS already limits this to the list price plus the rep's own branch classes. */
    @GET("price_list")
    suspend fun priceList(
        @Query("select") select: String = Selects.PRICE_LIST,
    ): List<PriceListDto>

    // -------------------------------------------------------------------------
    // Visits and workflow
    // -------------------------------------------------------------------------

    @POST("visit")
    @Headers("Prefer: return=minimal")
    suspend fun insertVisit(@Body visit: NewVisitDto): Response<Unit>

    @PATCH("visit")
    @Headers("Prefer: return=minimal")
    suspend fun updateVisit(
        @Query("id") id: String,
        @Body patch: JsonObject,
    ): Response<Unit>

    @GET("visit_step_result")
    suspend fun stepResults(
        @Query("visit_id") visitId: String,
        @Query("select") select: String = Selects.VISIT_STEP_RESULT,
    ): List<VisitStepResultDto>

    /**
     * Upsert. `resolution=merge-duplicates` plus the `on_conflict` key is
     * PostgREST's form of `on conflict do update`; a step is done or not done, so
     * recording it twice is not a different fact.
     */
    @POST("visit_step_result")
    @Headers("Prefer: resolution=merge-duplicates,return=minimal")
    suspend fun upsertStepResult(
        @Body row: JsonObject,
        @Query("on_conflict") onConflict: String = "visit_id,form_id",
    ): Response<Unit>

    // -------------------------------------------------------------------------
    // Stock counts
    // -------------------------------------------------------------------------

    @GET("stock_count")
    suspend fun stockCounts(
        @Query("customer_id") customerId: String,
        @Query("visit_id") exceptVisitId: String,
        @Query("select") select: String = Selects.PREVIOUS_COUNT,
        @Query("order") order: String = "count_date.desc,created_at.desc",
        @Query("limit") limit: Int = 1,
    ): List<PreviousCountDto>

    // -------------------------------------------------------------------------
    // Functions
    //
    // Both write several tables in one transaction and are idempotent on the
    // client-minted id, which is why they are functions and not inserts.
    // -------------------------------------------------------------------------

    @POST("rpc/submit_order")
    suspend fun submitOrder(@Body body: JsonObject): Response<Unit>

    @POST("rpc/submit_stock_count")
    suspend fun submitStockCount(@Body body: JsonObject): Response<Unit>
}

/**
 * Column lists. Every one of these has to match the DTO it decodes into: ask for
 * a column the DTO lacks and it is ignored, omit one the DTO requires and the
 * decode fails at runtime.
 */
object Selects {
    const val SALESPERSON = "id,code,full_name,branch_id,branch:branch_id(id,code,name)"
    const val SETTING = "key,value"
    const val REASON_CODE = "id,code,name,kind"
    const val SALES_STEP = "form_id,step,title_key,is_required,config"
    const val TRANSLATION = "key,value"

    const val ROUTE_CUSTOMER =
        "visit_order,customer:customer_id(id,code,name,address,phone,lat,lng," +
            "avatar_url,checkin_radius_m,class_id)"

    const val VISIT = "id,customer_id,status,check_in_at,check_out_at"
    const val VISIT_STEP_RESULT = "form_id,completed_at"

    const val PRODUCT = "id,code,name,base_uom,vat_basis_points,category:category_id(name,sort_order)"
    const val PRODUCT_UOM =
        "product_id,uom_code,conversion_rate,is_default_sale,sort_order,uom:uom_code(name)"
    const val PRICE_LIST = "product_id,uom_code,class_id,price,from_date,to_date"

    const val PREVIOUS_COUNT =
        "id,count_date,lines:stock_count_line(product_id,uom_code,qty,base_qty)"
}

/** PostgREST filter values. The operator is part of the value, not the key. */
object Filters {
    const val IS_TRUE = "eq.true"

    fun eq(value: String) = "eq.$value"

    fun neq(value: String) = "neq.$value"

    /** Array contains a single element, e.g. weekday 5 -> `cs.{5}`. */
    fun arrayContains(value: Int) = "cs.{$value}"
}
