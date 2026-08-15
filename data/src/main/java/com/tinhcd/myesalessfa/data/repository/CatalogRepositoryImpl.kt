package com.tinhcd.myesalessfa.data.repository

import com.tinhcd.myesalessfa.data.local.CatalogDao
import com.tinhcd.myesalessfa.data.local.MslEntity
import com.tinhcd.myesalessfa.data.local.MslItemEntity
import com.tinhcd.myesalessfa.data.local.PriceRuleEntity
import com.tinhcd.myesalessfa.data.local.ProductEntity
import com.tinhcd.myesalessfa.data.local.SaleUnitEntity
import com.tinhcd.myesalessfa.data.remote.service.CatalogueService
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.MslDefinition
import com.tinhcd.myesalessfa.domain.model.MslItem
import com.tinhcd.myesalessfa.domain.model.PriceRule
import com.tinhcd.myesalessfa.domain.model.PricedProduct
import com.tinhcd.myesalessfa.domain.model.Product
import com.tinhcd.myesalessfa.domain.model.SaleUnit
import com.tinhcd.myesalessfa.domain.model.mslFor
import com.tinhcd.myesalessfa.domain.model.priceCatalogue
import com.tinhcd.myesalessfa.domain.repository.CatalogRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The catalogue is cached because an order is built standing in a shop. Pulling
 * a few hundred products and their prices per visit over the connection a rep
 * actually has is not viable, and the price list is what the whole order depends
 * on being correct.
 */
@Singleton
class CatalogRepositoryImpl @Inject constructor(
    private val service: CatalogueService,
    private val dao: CatalogDao,
) : CatalogRepository {

    /**
     * One call where there were three, with units already nested inside their
     * product and paging handled by the function. Those three reads had no limit,
     * so on a real catalogue they would have hit the row cap and arrived silently
     * truncated — the app would have run on a partial product list with nothing to
     * say so.
     */
    override suspend fun refresh(): DataResult<Unit> = try {
        val catalogue = service.catalogue()
        val products = catalogue.products

        // RLS already limits these to the list price and the classes in the rep's
        // own branch, so there is no filter here to forget.
        val prices = catalogue.priceRules

        // Replaced wholesale rather than merged: a product withdrawn upstream, or
        // a price row that ended, has to disappear here too. Leaving one behind
        // means quoting a customer a price that no longer exists.
        dao.clearPriceRules()
        dao.clearSaleUnits()
        dao.clearProducts()

        dao.upsertProducts(
            products.map {
                ProductEntity(
                    id = it.id,
                    code = it.code,
                    name = it.name,
                    categoryName = it.categoryName,
                    categorySort = it.categorySort,
                    baseUom = it.baseUom,
                    vatBasisPoints = it.vatBasisPoints,
                )
            },
        )

        // Nested inside their product by the function, so there is no grouping to
        // do and no orphan possible — a unit cannot arrive without its product.
        dao.upsertSaleUnits(
            products.flatMap { product ->
                product.units.map {
                    SaleUnitEntity(
                        productId = product.id,
                        uomCode = it.uomCode,
                        uomName = it.uomName,
                        conversionRate = it.conversionRate,
                        isDefaultSale = it.isDefaultSale,
                        sortOrder = it.sortOrder,
                    )
                }
            },
        )

        // Prices still arrive flat, so a rule for a product that did not come back
        // — an inactive one — is dropped rather than left pointing at nothing.
        val known = products.mapTo(mutableSetOf()) { it.id }

        dao.upsertPriceRules(
            prices.filter { it.productId in known }.map {
                PriceRuleEntity(
                    productId = it.productId,
                    uomCode = it.uomCode,
                    classId = it.classId,
                    price = it.price,
                    fromDate = it.fromDate,
                    toDate = it.toDate,
                )
            },
        )

        dao.clearMslItems()
        dao.clearMsl()
        dao.upsertMsl(
            catalogue.msl.map {
                MslEntity(
                    id = it.id,
                    code = it.code,
                    channelId = it.channelId,
                    shopTypeId = it.shopTypeId,
                    fromDate = it.fromDate,
                    toDate = it.toDate,
                )
            },
        )
        dao.upsertMslItems(
            catalogue.msl.flatMap { list ->
                // A required SKU that is no longer in the catalogue cannot be
                // counted, so keeping the obligation would mark the outlet
                // permanently non-compliant for something it cannot buy.
                list.items.filter { it.productId in known }.map {
                    MslItemEntity(
                        mslId = list.id,
                        productId = it.productId,
                        minBaseQty = it.minBaseQty,
                    )
                }
            },
        )

        DataResult.Success(Unit)
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }

    /**
     * Reads the cache and hands the resolution rules to :domain. The repository
     * deliberately does not decide which price wins — that rule is tested
     * without a database in front of it.
     */
    override suspend fun catalogue(
        classId: String?,
        on: LocalDate,
    ): DataResult<List<PricedProduct>> = try {
        val products = dao.products().map {
            Product(
                id = it.id,
                code = it.code,
                name = it.name,
                categoryName = it.categoryName,
                baseUomCode = it.baseUom,
                vatBasisPoints = it.vatBasisPoints,
            )
        }

        val unitsByProduct = dao.saleUnits()
            .groupBy { it.productId }
            .mapValues { (_, rows) ->
                rows.map {
                    SaleUnit(
                        uomCode = it.uomCode,
                        uomName = it.uomName,
                        conversionRate = it.conversionRate,
                        isDefault = it.isDefaultSale,
                        sortOrder = it.sortOrder,
                    )
                }
            }

        val rules = dao.priceRules().mapNotNull { row ->
            // A row whose dates will not parse cannot be priced from, and
            // guessing at it would put a wrong number in front of a customer.
            val from = row.fromDate.toLocalDateOrNull() ?: return@mapNotNull null
            val to = row.toDate.toLocalDateOrNull() ?: return@mapNotNull null
            PriceRule(
                productId = row.productId,
                uomCode = row.uomCode,
                classId = row.classId,
                price = row.price,
                fromDate = from,
                toDate = to,
            )
        }

        DataResult.Success(
            priceCatalogue(
                products = products,
                unitsByProduct = unitsByProduct,
                priceRules = rules,
                classId = classId,
                on = on,
            ),
        )
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
    /**
     * Reads the cached lists and hands the scoping rule to :domain, the same
     * division as pricing: the repository fetches, the domain decides. The rule
     * here is a union with the strictest par level winning, which is tested
     * without a database in front of it.
     */
    override suspend fun mustStock(
        channelId: String?,
        shopTypeId: String?,
        on: LocalDate,
    ): DataResult<Map<String, Int>> = try {
        val itemsByList = dao.mslItems().groupBy { it.mslId }

        val definitions = dao.msl().mapNotNull { row ->
            // A list whose dates will not parse cannot be scoped by date, and
            // guessing would either impose obligations that have ended or drop ones
            // in force.
            val from = row.fromDate.toLocalDateOrNull() ?: return@mapNotNull null
            val to = row.toDate.toLocalDateOrNull() ?: return@mapNotNull null
            MslDefinition(
                id = row.id,
                code = row.code,
                channelId = row.channelId,
                shopTypeId = row.shopTypeId,
                fromDate = from,
                toDate = to,
                items = itemsByList[row.id].orEmpty().map {
                    MslItem(productId = it.productId, minBaseQty = it.minBaseQty)
                },
            )
        }

        DataResult.Success(definitions.mslFor(channelId, shopTypeId, on))
    } catch (e: Exception) {
        DataResult.Failure(e.toAppError())
    }
}

private fun String.toLocalDateOrNull(): LocalDate? =
    runCatching { LocalDate.parse(this) }.getOrNull()
