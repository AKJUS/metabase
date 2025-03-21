/* eslint-disable react/prop-types */
import { Component } from "react";

import { deserializeCardFromUrl } from "metabase/lib/card";
import { connect } from "metabase/lib/redux";
import { loadMetadataForCard } from "metabase/questions/actions";
import { getMetadata } from "metabase/selectors/metadata";
import Question from "metabase-lib/v1/Question";

// type annotations

/*
 * AdHocQuestionLoader
 *
 * Load a transient quetsion via its encoded URL and return it to the calling
 * component
 *
 * @example
 *
 * Render prop style
 * import AdHocQuestionLoader from 'metabase/containers/AdHocQuestionLoader'
 *
 * // assuming
 * class ExampleAdHocQuestionFeature extends React.Component {
 *    render () {
 *      return (
 *        <AdHocQuestionLoader questionId={this.props.params.questionId}>
 *        { ({ question, loading, error }) => {
 *
 *        }}
 *        </SavedQuestion>
 *      )
 *    }
 * }
 *
 * @example
 *
 * The raw un-connected component is also exported so we can unit test it
 * without the redux store.
 */
export class AdHocQuestionLoader extends Component {
  state = {
    // this will store the loaded question
    question: null,
    // keep a reference to the card as well to help with re-creating question
    // objects if the underlying metadata changes
    card: null,
    loading: false,
    error: null,
  };

  UNSAFE_componentWillMount() {
    // load the specified question when the component mounts
    this._loadQuestion(this.props.questionHash);
  }

  UNSAFE_componentWillReceiveProps(nextProps) {
    // if the questionHash changes (this will most likely be the result of a
    // url change) then we need to load this new question
    if (nextProps.questionHash !== this.props.questionHash) {
      this._loadQuestion(nextProps.questionHash);
    }

    // if the metadata changes for some reason we need to make sure we
    // update the question with that metadata
    if (nextProps.metadata !== this.props.metadata && this.state.card) {
      this.setState({
        question: new Question(this.state.card, nextProps.metadata),
      });
    }
  }

  /*
   * Load an AdHoc question and any required metadata
   *
   * 1. Decode the question via the URL
   * 2. Load any required metadata into the redux store
   * 3. Create a new Question object to return to metabase-lib methods can
   *    be used
   * 4. Set the component state to the new Question
   */
  async _loadQuestion(questionHash) {
    if (!questionHash) {
      this.setState({
        loading: false,
        error: null,
        question: null,
        card: null,
      });
      return;
    }
    try {
      this.setState({ loading: true, error: null });
      // get the card definition from the URL, the "card"
      const card = deserializeCardFromUrl(questionHash);
      // pass the decoded card to load any necessary metadata
      // (tables, source db, segments, etc) into
      // the redux store, the resulting metadata will be avaliable as metadata on the
      // component props once it's avaliable
      await this.props.loadMetadataForCard(card);

      // instantiate a new question object using the metadata and saved question
      // so we can use metabase-lib methods to retrieve information and modify
      // the question
      const question = new Question(card, this.props.metadata);

      // finally, set state to store the Question object so it can be passed
      // to the component using the loader, keep a reference to the card
      // as well
      this.setState({ loading: false, question, card });
    } catch (error) {
      this.setState({ loading: false, error });
    }
  }

  render() {
    const { children } = this.props;
    const { question, loading, error } = this.state;
    // call the child function with our loaded question
    return children && children({ question, loading, error });
  }
}

// redux stuff
function mapStateToProps(state) {
  return {
    metadata: getMetadata(state),
  };
}

const mapDispatchToProps = {
  loadMetadataForCard,
};

export default connect(
  mapStateToProps,
  mapDispatchToProps,
)(AdHocQuestionLoader);
