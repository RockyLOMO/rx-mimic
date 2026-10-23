import unittest
from unittest.mock import MagicMock, patch, call
import urllib.error

from verify_products import Api, VerificationFailed, verify_platform, verify_tb_analysis, verify_tb_cache, main


class ProductVerificationTest(unittest.TestCase):
    def cache_api(self):
        api = MagicMock()
        promotion = {'productId': '123456789', 'identityResolved': True,
                     'promotionUrl': 'https://e.tb.cn/private-link', 'createdAt': 123456789,
                     'promotionToken': 'private-token'}
        search = {'items': [{'productId': '123456789', 'identityResolved': True}]}
        api.task.side_effect = [dict(promotion), dict(promotion, cacheHit=True),
                                dict(search), dict(search, cacheHit=True)]
        api.call.return_value = {'productId': '123456789',
                                 'basic': {'productId': '123456789', 'price': '99.00'},
                                 'rebate': {'productId': '123456789', 'commissionRate': '10%'}}
        return api

    def test_cache_check_repeats_same_inputs_and_prints_only_safe_numeric_id(self):
        api = self.cache_api()
        with patch('builtins.print') as output:
            verify_tb_cache(api, '123456789')
        api.task.assert_has_calls([
            call('getTbPromotionUrl', {'keyword': '123456789', 'adSiteName': '0'}),
            call('getTbPromotionUrl', {'keyword': '123456789', 'adSiteName': '0'}),
            call('tbUnionSearchProducts', {'keyword': '123456789', 'maxResults': 2}),
            call('tbUnionSearchProducts', {'keyword': '123456789', 'maxResults': 2})])
        self.assertEqual(4, api.task.call_count)
        api.call.assert_called_once_with('/products/tb/123456789')
        output.assert_called_once_with('TB_CACHE_VERIFY_OK productId=123456789', flush=True)

    def test_cache_check_rejects_promotion_miss_and_changed_identity_link_or_created_at(self):
        for changed, code in [({'cacheHit': False}, 'CACHE_PROMOTION_MISS'),
                              ({'productId': '999999999'}, 'CACHE_PROMOTION_IDENTITY_MISMATCH'),
                              ({'promotionUrl': 'https://e.tb.cn/changed-private'}, 'CACHE_PROMOTION_LINK_CHANGED'),
                              ({'createdAt': 999}, 'CACHE_PROMOTION_CREATED_AT_CHANGED')]:
            with self.subTest(code=code):
                api = self.cache_api()
                responses = list(api.task.side_effect)
                responses[1].update(changed)
                api.task.side_effect = responses
                with patch('builtins.print') as output:
                    with self.assertRaisesRegex(VerificationFailed, '^' + code + '$'):
                        verify_tb_cache(api, '123456789')
                output.assert_not_called()

    def test_cache_check_rejects_missing_master_fields(self):
        api = self.cache_api()
        api.call.return_value = {'productId': '123456789', 'basic': {'productId': '123456789'}, 'rebate': {}}
        with self.assertRaisesRegex(VerificationFailed, '^CACHE_MASTER_FIELDS_MISSING$'):
            verify_tb_cache(api, '123456789')

    def test_cache_check_rejects_search_miss_and_non_numeric_identity(self):
        for changed, code in [({'cacheHit': False}, 'CACHE_SEARCH_MISS'),
                              ({'items': [{'productId': 'opaque-private-material', 'identityResolved': False}]},
                               'CACHE_SEARCH_IDENTITY_MISMATCH')]:
            with self.subTest(code=code):
                api = self.cache_api()
                responses = list(api.task.side_effect)
                responses[3].update(changed)
                api.task.side_effect = responses
                with self.assertRaisesRegex(VerificationFailed, '^' + code + '$'):
                    verify_tb_cache(api, '123456789')

    def test_cache_check_invalid_id_never_calls_api(self):
        api = MagicMock()
        with self.assertRaisesRegex(VerificationFailed, '^INVALID_NUMERIC_ID$'):
            verify_tb_cache(api, 'private-material')
        api.task.assert_not_called()
        api.call.assert_not_called()

    def test_cache_check_cli_routes_to_cache_verification_only(self):
        with patch('sys.argv', ['verify_products.py', '--access-file', 'private.json',
                                '--tb-product-id', '123456789', '--cache-check']), \
                patch('verify_products.Path.read_text', return_value='{"baseUrl":"https://example.test","apiKey":"secret"}'), \
                patch('verify_products.Api') as api, \
                patch('verify_products.verify_tb_cache') as cache, \
                patch('verify_products.verify_tb_analysis') as analysis:
            self.assertEqual(0, main())
        cache.assert_called_once_with(api.return_value, '123456789')
        analysis.assert_not_called()

    def test_cache_check_cli_requires_numeric_product_argument(self):
        with patch('sys.argv', ['verify_products.py', '--access-file', 'private.json', '--cache-check']), \
                patch('sys.stderr'), patch('verify_products.Api') as api:
            with self.assertRaises(SystemExit) as error:
                main()
        self.assertEqual(2, error.exception.code)
        api.assert_not_called()

    def test_analysis_checks_identity_for_each_input_and_persisted_source_price(self):
        api = MagicMock()
        promotion = {'productId': '123456789', 'identityResolved': True,
                     'promotionUrl': 'https://s.click.taobao.com/private',
                     'productInfo': {'price': '105.00', 'priceField': 'unionAnalysisPrice'}}
        exact = {'items': [{'productId': '123456789', 'identityResolved': True}]}
        api.task.side_effect = [promotion, exact, exact, exact]
        api.call.side_effect = [[{'id': 1, 'platform': 'tb', 'productId': '123456789'}],
                                [{'rawPrice': '105.00', 'sourceField': 'unionAnalysisPrice'}]]
        with patch('builtins.print'):
            verify_tb_analysis(api, '123456789')
        self.assertEqual(4, api.task.call_count)

    def test_analysis_generated_link_without_verified_identity_fails(self):
        api = MagicMock()
        api.task.return_value = {'productId': '999999999', 'identityResolved': True}
        with self.assertRaisesRegex(VerificationFailed, 'ANALYSIS_IDENTITY_MISMATCH'):
            verify_tb_analysis(api, '123456789')
        api.call.assert_not_called()

    def test_failed_task_reports_safe_live_gateway_code(self):
        api = Api({'baseUrl': 'https://example.test/api/v1', 'apiKey': 'secret'})
        api.call = MagicMock(side_effect=[{'taskId': '123'},
                            {'status': 'FAILED', 'errorCode': 'BUSINESS_FAILED'},
                            {'failureCode': 'TB_GATEWAY_NUMERIC_MISMATCH'}])
        with self.assertRaisesRegex(VerificationFailed, 'TB_GATEWAY_NUMERIC_MISMATCH$'):
            api.task('getTbPromotionUrl', {})

    def test_completed_task_with_timeout_business_result_is_not_success(self):
        api = Api({'baseUrl': 'https://example.test/api/v1', 'apiKey': 'secret'})
        api.call = MagicMock(side_effect=[{'taskId': '123'}, {'status': 'SUCCEEDED'}, {'status': 'TIMEOUT'}])
        with self.assertRaisesRegex(VerificationFailed, '^tbUnionSearchProducts_BUSINESS_TIMEOUT$'):
            api.task('tbUnionSearchProducts', {})
        self.assertEqual(3, api.call.call_count)

    def test_manual_action_cancels_own_task_and_fails(self):
        api = Api({'baseUrl': 'https://example.test/api/v1', 'apiKey': 'secret'})
        api.call = MagicMock(side_effect=[{'taskId': '123'}, {'status': 'WAITING_USER'}, {}])
        with self.assertRaisesRegex(VerificationFailed, 'MANUAL_ACTION_REQUIRED'):
            api.task('tbUnionSearchProducts', {})
        self.assertEqual(api.call.call_args.args, ('/tasks/123/cancel', {}))

    def test_transport_failure_does_not_echo_material_or_secret(self):
        api = Api({'baseUrl': 'https://example.test/api/v1', 'apiKey': 'secret'})
        error = urllib.error.HTTPError('https://private-material', 401, 'secret', {}, None)
        with patch('urllib.request.urlopen', side_effect=error):
            with self.assertRaisesRegex(VerificationFailed, '^HTTP_401$'):
                api.call('/tasks')

    def test_generated_link_without_numeric_identity_fails_acceptance(self):
        api = MagicMock()
        api.task.side_effect = [{'items': [{'encryptedProductId': 'opaque'}]},
                                {'identityResolved': False, 'promotionUrl': 'https://private-material'}]
        with self.assertRaisesRegex(VerificationFailed, 'NUMERIC_ID_UNRESOLVED'):
            verify_platform(api, 'tb')

    def test_numeric_promotion_with_missing_price_history_fails(self):
        api = MagicMock()
        promotion = {'identityResolved': True, 'productId': '123'}
        search = {'items': [{'encryptedProductId': 'opaque'}]}
        api.task.side_effect = [search, promotion, search, search, promotion]
        api.call.side_effect = [[{'id': 1, 'platform': 'jd', 'productId': '123'}], []]
        with self.assertRaisesRegex(VerificationFailed, 'PRICE_NOT_INGESTED'):
            verify_platform(api, 'jd')
