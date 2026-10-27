import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTypeScript } from './load-typescript.mjs';

const {
  effectiveRunStatus, mayResumeRun, missingInformation, stepPresentation,
  questionnaireAnswers, visibleOperationalError, validateFormFields, registrationQuestions,
} = await loadTypeScript('../src/lib/activity-view.ts');

test('waiting input outranks the successfully finished background job', () => {
  const run = { status: 'succeeded', agent_run: { status: 'waiting_input' }, result: { status: 'waiting_input', data: { missing_information: ['请确认活动时间', '', '请确认活动时间', 4] } } };
  assert.equal(effectiveRunStatus(run), 'waiting_input');
  assert.deepEqual(missingInformation(run), ['请确认活动时间']);
  assert.equal(mayResumeRun(effectiveRunStatus(run)), true);
  assert.equal(effectiveRunStatus({ status: 'queued' }), 'pending');
});

test('only failures and missing input can be resumed; terminal runs have no recovery action', () => {
  for (const status of ['succeeded', 'cancelled', 'running', 'pending', '']) assert.equal(mayResumeRun(status), false);
  assert.equal(mayResumeRun('failed'), true);
  assert.notEqual(stepPresentation('failed').symbol, stepPresentation('done').symbol);
  assert.notEqual(stepPresentation('pending').symbol, stepPresentation('done').symbol);
  assert.equal(stepPresentation('waiting_input').label, '等待补充');
  assert.equal(stepPresentation().label, '状态未记录');
});

test('questionnaire answers use schema labels, preserve false/zero, and retain historical answers', () => {
  const fields = [
    { name: 'name', label: '姓名' }, { name: 'contact', label: '联系方式' },
    { name: 'transport', label: '出行方式' }, { name: 'guest', label: '同行人数' },
    { name: 'available', label: '是否参加' }, { name: 'notes', label: '备注' },
  ];
  assert.deepEqual(questionnaireAnswers(fields, { transport: ['大巴', '步行'], guest: 0, available: false, old_question: '历史回答', name: '小张' }), [
    { name: 'transport', label: '出行方式', value: '大巴、步行' },
    { name: 'guest', label: '同行人数', value: '0' },
    { name: 'available', label: '是否参加', value: '否' },
    { name: 'notes', label: '备注', value: '未填写' },
    { name: 'old_question', label: 'old_question（历史字段）', value: '历史回答' },
  ]);
});

test('operational errors cannot expose a credential or upstream URL', () => {
  for (const value of ['Authorization: Bearer abc', 'api_key=abc', 'token: abc', 'password=abc', 'https://provider.example/raw']) {
    assert.equal(visibleOperationalError(value, '安全提示'), '安全提示');
  }
  assert.equal(visibleOperationalError('请先填写活动时间'), '请先填写活动时间');
});

test('registration fields preserve edited metadata without mutating the saved draft', () => {
  const original = [{ name: 'contact', label: '手机号码', type: 'text', required: false, options: ['保留'] }, { name: 'campus', label: '校区', type: 'select', options: ['北区', '南区'] }];
  const result = registrationQuestions(original);
  assert.deepEqual(result.map((field) => field.name), ['contact', 'campus', 'name']);
  assert.equal(result[0].label, '手机号码');
  assert.equal(result[0].required, true);
  result[1].options.push('东区');
  assert.deepEqual(original[1].options, ['北区', '南区']);
  assert.equal(original[0].required, false);
});

test('invalid questions fail before saving and valid select questions round-trip', () => {
  assert.throws(() => validateFormFields([{ name: 'food', label: '口味', type: 'select', options: [] }]));
  assert.throws(() => validateFormFields([{ name: 'food', label: '口味', type: 'select', options: ['清淡', '清淡'] }]));
  assert.throws(() => validateFormFields([{ name: 'notes', label: ' ', type: 'text' }]));
  assert.throws(() => validateFormFields([{ name: 'same', label: 'A', type: 'text' }, { name: 'same', label: 'B', type: 'text' }]));
  const result = validateFormFields([{ name: 'food', label: ' 口味 ', type: 'select', required: true, options: [' 清淡 ', '辣'] }]);
  assert.deepEqual(result[0], { name: 'food', label: '口味', type: 'select', required: true, options: ['清淡', '辣'] });
});
