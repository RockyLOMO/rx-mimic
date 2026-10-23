import unittest
from unittest.mock import MagicMock, patch
import urllib.error

from verify_products import Api, VerificationFailed, verify_platform, verify_tb_analysis


class ProductVerificationTest(unittest.TestCase):
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
