import unittest
from unittest.mock import MagicMock, patch
import urllib.error

from verify_products import Api, VerificationFailed, verify_platform


class ProductVerificationTest(unittest.TestCase):
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
