import io.grpc.stub.StreamObserver;
import ipl.isel.cn.group2.contract.Submission;

public class SubmissionObserver implements StreamObserver<Submission>
{
    volatile private Submission submission = null;
    volatile private Throwable message = null;

    volatile private boolean done = false;
    volatile private boolean error = false;

    @Override
    public void onNext(Submission submission) {
        this.submission = submission;
    }

    @Override
    public void onError(Throwable throwable) {
        message = throwable;
        error = true;
        done = true;
    }

    @Override
    public void onCompleted() {
        done = true;
    }

    public Throwable getMessage() {
        return message;
    }

    public boolean hasError() {
        return error;
    }

    public boolean isDone() {
        return done;
    }

    public Submission getSubmissionId() {
        return submission;
    }
}
