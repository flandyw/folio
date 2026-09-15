// Regenerate with: node tools/examtrack-scheduler-fixtures.cjs ../examtrack
// Executes the ACTUAL TypeScript scheduler, not a second implementation.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(process.argv[2]);
const ts = require(path.join(root, 'node_modules/typescript'));
const source = fs.readFileSync(path.join(root, 'src/lib/exam-data.ts'), 'utf8');
const start = source.indexOf('const DAY_MS =');
const end = source.indexOf('export type CoverageArea', start);
const code = ts.transpileModule(source.slice(start, end), { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText;
const sandbox = { exports: {}, crypto: { randomUUID: () => 'review-fixture' } };
vm.runInNewContext(code, sandbox);
const api = sandbox.exports;
const at = '2026-09-16T02:00:00.000Z';
const base = {id:'11111111-1111-4111-8111-111111111111', attemptId:'22222222-2222-4222-8222-222222222222', question:'Question 8c', questionText:'Find the derivative.', category:'Reasoning', explanation:'Chain rule omitted.', correction:'Apply the chain rule.', totalMarks:4, marksLost:2, resolved:false, createdAt:'2026-09-01T00:00:00.000Z', updatedAt:'2026-09-01T00:00:00.000Z', futureField:{nested:['keep', 42]}};
const variants = [base, {...base, resolved:true}, ...['new','learning','review','relearning'].flatMap(reviewState => [0,1,2.5,7,21,40].flatMap(intervalDays => [1.3,2.5,2.65].map(easeFactor => ({...base, reviewState,intervalDays,easeFactor,repetitions:3,lapses:2})))), ...['incorrect','assisted','correct','easy'].map(result => ({...base, reviewHistory:[{id:'legacy', completedAt:'2026-09-10T00:00:00.000Z',result}]})), {...base, reviewHistory:[{id:'a',completedAt:at,result:'correct'},{id:'b',completedAt:at,result:'correct',intervalDays:12}]}, {...base, dueAt:null, lastReviewedAt:'2026-09-12T00:00:00.000Z', intervalDays:4}];
const cases = variants.flatMap(input => ['again','hard','good','easy'].map(rating => ({input,rating,at,schedule:api.getMistakeSchedule(input),preview:api.previewMistakeReview(input,rating,at),record:api.recordMistakeReview(input,rating,at)})));
fs.writeFileSync('app/src/test/resources/examtrack/scheduler.json', '[\n'+cases.map(c => JSON.stringify(c)).join(',\n')+'\n]\n');
console.log(`Wrote ${cases.length} fixtures from ExamTrack's TypeScript scheduler`);
